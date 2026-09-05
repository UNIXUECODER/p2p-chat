package com.p2pchat.daemon.session;

import com.p2pchat.filetransfer.ChunkCipher;
import com.p2pchat.filetransfer.EncryptedChunk;
import com.p2pchat.filetransfer.FileChunker;
import com.p2pchat.filetransfer.FileKey;
import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.TransferState;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M6g-3: consolidates {@code FileSenderMain}/{@code FileReceiverMain}'s (M4c/M4d) proven chunk
 * logic — offer/request/chunk negotiation, AES-256-GCM per-chunk encryption, storage-backed
 * resume via {@code missingChunks}/{@code markChunkReceived} — into a real {@link
 * FileTransferHandler} implementation {@link SessionManager} can hold for the life of the daemon,
 * serving any number of concurrent transfers with any number of peers, rather than the one-shot,
 * single-transfer shape those demo Mains needed.
 *
 * <p>This is a port, not a transcription — a few real, deliberate behavioral differences from the
 * demos' proven logic, each because the daemon context genuinely calls for it:
 *
 * <ul>
 *   <li><b>A real accept gate.</b> {@code FileReceiverMain} requested chunks immediately on
 *   receiving any offer — reasonable for a demo proving the wire mechanics work, but this
 *   project's own spec (§7) has a separate {@code files.accept} RPC method, and the schema's
 *   own {@code TransferState} enum already distinguishes {@code OFFERED} from {@code ACCEPTED}
 *   from {@code IN_PROGRESS} — distinctions the demo's logic never actually used. {@link
 *   #onFileOffer} now stops at {@code OFFERED} and fires {@link
 *   DaemonEventListener#onFileOfferReceived}; chunks aren't requested until {@link
 *   #acceptFileTransfer} is actually called.</li>
 *   <li><b>Conversation IDs match chat's.</b> The demo used an ad-hoc {@code "direct-" +
 *   senderPeerId} placeholder — from before {@code SessionManager}'s own {@code
 *   deriveDirectConversationId} (the canonical, sorted-pair scheme chat messages actually use)
 *   existed. Reusing it here means a file transfer and a chat message between the same two peers
 *   land in the same conversation, not two different ones.</li>
 *   <li><b>Sends go through {@code OutboundMessageService}, not raw {@code sendEnvelope}.</b> The
 *   demos predate {@code ConnectionStrategy}/{@code OutboundMessageService} (M6b) entirely, so
 *   they never had relay fallback — routing file-transfer replies through the same {@link
 *   FileTransferHandler.EncryptAndSend} seam chat already uses means a chunk request or a chunk
 *   reply now gets the same direct-then-relay fallback a chat message does, for free.</li>
 *   <li><b>No manual {@code CompletableFuture.runAsync} wrapping for the Netty-deadlock fix the
 *   demos needed.</b> Verified, not assumed: {@code OutboundMessageService.send} already runs the
 *   actual blocking work on its own dedicated pool via {@code CompletableFuture.supplyAsync}, and
 *   this handler is only ever called from {@code SessionManager}'s {@code inboundExecutor} — a
 *   plain dedicated thread, never jvm-libp2p's own Netty event-loop thread the demos' fix was
 *   actually protecting against. The underlying reason for that fix doesn't apply here.</li>
 *   <li><b>A hash mismatch is recorded, not just printed.</b> The demo's {@code completeTransfer}
 *   only ever printed "M4d FAILED" to the console. A real daemon transitions the transfer to
 *   {@code TransferState.FAILED} so a future caller querying transfer state sees it.</li>
 *   <li><b>A re-offer for an already-accepted transfer doesn't un-accept it.</b> A genuine, if
 *   narrow, correctness gap a naive port would have reintroduced: if a sender resends an offer
 *   (e.g. its own send timed out and it retried) for a transfer this daemon already accepted and
 *   is actively receiving chunks for, blindly overwriting this handler's in-memory bookkeeping
 *   would discard the chosen save path mid-transfer. {@link #onFileOffer} checks for this and
 *   leaves an already-accepted transfer's state alone.</li>
 * </ul>
 *
 * <p><b>Known limitation, named rather than silently accepted:</b> a transfer sitting at {@code
 * OFFERED} — received, but not yet accepted — does not survive a daemon restart. The offer's
 * negotiation-critical details (the AES file key, the sender's reply address) live only in this
 * handler's in-memory maps, deliberately never persisted in cleartext — the same security posture
 * {@code FileKey} itself already takes (see its own Javadoc: no exposed accessor beyond a
 * defensive copy, a redacted {@code toString}). A restart between offer and accept means the
 * sender needs to re-offer; this is the identical shape of gap {@code SessionManager}'s own
 * Javadoc already names for relay-delivered inbound reception — a real, deliberately out-of-scope
 * limitation, not a silent one. Similarly, {@code file_transfers.local_path} is left {@code null}
 * for now rather than updated at accept time: nothing in this codebase reads it yet (no {@code
 * getFileTransfer}/{@code files.list} exists), and guessing at that method's real needs before it
 * exists would be exactly the kind of premature design this project has consistently avoided
 * elsewhere (see {@code core-storage.StorageService}'s own M6g-1/M6g-2 Javadoc notes for the same
 * instinct applied to read-side gaps).
 */
public final class DefaultFileTransferHandler implements FileTransferHandler {

    public static final Duration DEFAULT_OUTGOING_TRANSFER_TTL = Duration.ofHours(24);
    // pre-m6h-hardening-plan.md finding C-3: "add a configurable maximum accepted file size,
    // surface offers over it to the UI rather than silently accepting." No UI exists yet to
    // surface a rejection to (see this class's own Javadoc on not designing for a caller that
    // doesn't exist) -- offers over the limit are logged and rejected the same way a duplicate or
    // already-completed offer already is, which is the real, present behavior this can build on
    // once M7 exists. 2 GiB is a starting default, not a researched number -- easy to override via
    // the 4-arg constructor below.
    public static final long DEFAULT_MAX_ACCEPTED_FILE_SIZE_BYTES = 2L * 1024 * 1024 * 1024;

    private final StorageService storage;
    private final DaemonEventListener eventListener;
    private final Duration outgoingTransferTtl;
    private final long maxAcceptedFileSizeBytes;

    // Set once via attach() -- see that method's own Javadoc for why these can't be constructor
    // parameters the same way SessionManager's own ownPeerId/sessions can't be.
    private volatile EncryptAndSend encryptAndSend;
    private volatile PeerId ownPeerId;
    private volatile String ownAddress;

    // Transfers THIS node initiated via SessionManager.sendFile, keyed by transferId -- what's
    // needed to answer a FileChunkRequestPayload when it comes back.
    private final Map<String, OutgoingTransfer> outgoingTransfers = new ConcurrentHashMap<>();
    // Transfers offered TO this node, keyed by transferId -- both pending (outputFile == null,
    // not yet accepted) and accepted/in-progress (outputFile set). Completion state itself lives
    // in storage (missingChunks), not here -- mirrors FileReceiverMain's own ReceivingTransfer.
    private final Map<String, IncomingTransfer> incomingTransfers = new ConcurrentHashMap<>();

    public DefaultFileTransferHandler(StorageService storage, DaemonEventListener eventListener) {
        this(storage, eventListener, DEFAULT_OUTGOING_TRANSFER_TTL, DEFAULT_MAX_ACCEPTED_FILE_SIZE_BYTES);
    }

    public DefaultFileTransferHandler(StorageService storage, DaemonEventListener eventListener, Duration outgoingTransferTtl) {
        this(storage, eventListener, outgoingTransferTtl, DEFAULT_MAX_ACCEPTED_FILE_SIZE_BYTES);
    }

    public DefaultFileTransferHandler(StorageService storage, DaemonEventListener eventListener,
                                       Duration outgoingTransferTtl, long maxAcceptedFileSizeBytes) {
        this.storage = storage;
        this.eventListener = eventListener;
        this.outgoingTransferTtl = outgoingTransferTtl != null ? outgoingTransferTtl : DEFAULT_OUTGOING_TRANSFER_TTL;
        this.maxAcceptedFileSizeBytes = maxAcceptedFileSizeBytes;
    }

    @Override
    public void attach(EncryptAndSend encryptAndSend, PeerId ownPeerId, String ownAddress) {
        this.encryptAndSend = encryptAndSend;
        this.ownPeerId = ownPeerId;
        this.ownAddress = ownAddress;
    }

    @Override
    public void registerOutgoingTransfer(String transferId, Path sourceFile, FileKey fileKey, int chunkSize,
                                          PeerId targetPeerId, String targetDirectMultiaddr, String targetRelayMultiaddr) {
        evictExpiredOutgoingTransfers();
        // totalChunks computed from the real, local source file's actual size -- not trusted from
        // any caller-supplied value -- specifically so onFileChunkRequest below has something
        // authoritative to bounds-check a peer's requested indices against. See that method's own
        // comment on why this exists.
        long sourceFileSize;
        try {
            sourceFileSize = Files.size(sourceFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read size of outgoing transfer source file " + sourceFile, e);
        }
        int totalChunks = FileChunker.chunkCount(sourceFileSize, chunkSize);
        outgoingTransfers.put(transferId, new OutgoingTransfer(
                sourceFile, fileKey, chunkSize, totalChunks, targetPeerId, targetDirectMultiaddr, targetRelayMultiaddr));
    }

    private void evictExpiredOutgoingTransfers() {
        long cutoff = System.currentTimeMillis() - outgoingTransferTtl.toMillis();
        outgoingTransfers.entrySet().removeIf(entry -> entry.getValue().lastActivityEpochMs < cutoff);
    }

    @Override
    public void onFileOffer(PeerId sender, FileOfferPayload offer) {
        String rejection = validateOffer(offer);
        if (rejection != null) {
            // Rejected before any state is created -- no IncomingTransfer, no storage row, no
            // event fired. See DEFAULT_MAX_ACCEPTED_FILE_SIZE_BYTES's own Javadoc on why this
            // is "logged, not surfaced to a UI" for now: there is no UI yet to surface it to.
            System.out.println("[file] rejecting offer " + offer.transferId() + " from " + sender + ": " + rejection);
            return;
        }

        IncomingTransfer existing = incomingTransfers.get(offer.transferId());
        if (existing != null && existing.outputFile != null) {
            // Already accepted and (maybe) in progress -- a re-offer here is a sender retry, not
            // a reason to discard the save path this daemon already chose. See this class's own
            // Javadoc for why blindly overwriting would be a real correctness bug, not a
            // hypothetical one.
            System.out.println("[file] duplicate offer for already-accepted transfer " + offer.transferId() + " - ignoring");
            return;
        }

        TransferState persistedState = storage.getTransferState(offer.transferId());
        if (persistedState == TransferState.COMPLETED) {
            System.out.println("[file] duplicate offer for already-COMPLETED transfer " + offer.transferId() + " - ignoring");
            return;
        }
        if (persistedState == TransferState.FAILED) {
            System.out.println("[file] re-offer for previously-FAILED transfer " + offer.transferId() + " - resetting chunk state for retry");
            storage.resetChunkState(offer.transferId());
        }

        IncomingTransfer transfer = new IncomingTransfer(
                sender, offer.senderAddress(), offer.fileName(), offer.fileHash(),
                offer.totalChunks(), offer.chunkSize(), FileKey.fromBytes(offer.fileKey()));
        incomingTransfers.put(offer.transferId(), transfer);

        String conversationId = deriveDirectConversationId(ownPeerId.value(), sender.value());
        storage.saveFileMetadata(new FileTransfer(
                offer.transferId(), conversationId, offer.fileName(), offer.fileSize(), offer.fileHash(),
                offer.chunkSize(), offer.totalChunks(), TransferState.OFFERED, null, System.currentTimeMillis()));

        System.out.println("[file] offer received: \"" + offer.fileName() + "\" (" + offer.fileSize()
                + " bytes, " + offer.totalChunks() + " chunks) from " + sender + " - awaiting accept");

        eventListener.onFileOfferReceived(offer.transferId(), sender, offer.fileName(), offer.fileSize());
    }

    /**
     * pre-m6h-hardening-plan.md finding C-3: {@code FileOfferPayload} is entirely wire-supplied,
     * entirely attacker-controlled, and until this fix, entirely trusted. {@code totalChunks} in
     * particular flows straight into {@code storage.missingChunks(transferId, totalChunks)} both
     * here (indirectly, via the {@link IncomingTransfer} this creates) and in {@link
     * #acceptFileTransfer} — an offer claiming a multi-billion {@code totalChunks} would have
     * made that call build a multi-billion-entry result before a single byte of the file ever
     * existed. Returns a human-readable rejection reason, or {@code null} if the offer is
     * internally consistent and within configured bounds. Deliberately returns a reason rather
     * than throwing: an invalid offer from a peer is an expected, not exceptional, condition —
     * same treatment {@link #onFileOffer} already gives a duplicate or already-completed offer.
     */
    private String validateOffer(FileOfferPayload offer) {
        if (offer.chunkSize() < FileChunker.MIN_CHUNK_SIZE_BYTES || offer.chunkSize() > FileChunker.MAX_CHUNK_SIZE_BYTES) {
            return "chunkSize " + offer.chunkSize() + " outside allowed range ["
                    + FileChunker.MIN_CHUNK_SIZE_BYTES + ", " + FileChunker.MAX_CHUNK_SIZE_BYTES + "]";
        }
        if (offer.totalChunks() <= 0) {
            return "totalChunks " + offer.totalChunks() + " must be positive";
        }
        if (offer.fileSize() < 0) {
            return "fileSize " + offer.fileSize() + " is negative";
        }
        if (offer.fileSize() > maxAcceptedFileSizeBytes) {
            return "fileSize " + offer.fileSize() + " exceeds configured maximum " + maxAcceptedFileSizeBytes;
        }
        // Reuses the same ceil-div FileChunker.chunkCount already uses to compute totalChunks on
        // the sending side, rather than duplicating the formula -- so an offer is only accepted
        // if it's consistent with what an honest sender would itself have computed.
        int expectedTotalChunks = FileChunker.chunkCount(offer.fileSize(), offer.chunkSize());
        if (offer.totalChunks() != expectedTotalChunks) {
            return "totalChunks " + offer.totalChunks() + " inconsistent with fileSize/chunkSize (expected "
                    + expectedTotalChunks + ")";
        }
        return null;
    }

    /**
     * See {@link FileTransferHandler#acceptFileTransfer}'s own Javadoc for the contract. Computes
     * missing chunks from storage, not an assumption of "none yet" — the same {@code
     * missingChunks} call {@code FileReceiverMain} used for M4d's resume property applies
     * unchanged here: if this {@code transferId} already has chunks marked received from before a
     * restart (the sender re-offered, this handler rebuilt a fresh {@code IncomingTransfer}, but
     * {@code file_chunk_state} itself survived), only the genuinely still-missing chunks get
     * requested.
     */
    @Override
    public void acceptFileTransfer(String transferId, Path savePath) {
        IncomingTransfer transfer = incomingTransfers.get(transferId);
        if (transfer == null) {
            System.out.println("[file] acceptFileTransfer for unknown/expired offer " + transferId + " - ignoring");
            return;
        }
        synchronized (transfer) {
            if (transfer.outputFile != null) {
                System.out.println("[file] acceptFileTransfer called for already-accepted transfer " + transferId + " - ignoring");
                return;
            }
            transfer.outputFile = savePath;
        }
        storage.updateTransferState(transferId, TransferState.ACCEPTED);

        List<Integer> missing = storage.missingChunks(transferId, transfer.totalChunks);
        if (missing.isEmpty()) {
            completeTransfer(transferId, transfer);
            return;
        }

        storage.updateTransferState(transferId, TransferState.IN_PROGRESS);
        int[] missingArray = missing.stream().mapToInt(Integer::intValue).toArray();
        FileChunkRequestPayload request = new FileChunkRequestPayload(transferId, missingArray);
        encryptAndSend.encryptAndSend(transfer.senderPeerId, transfer.senderAddress, null, request);
    }

    @Override
    public void onFileChunkRequest(PeerId sender, FileChunkRequestPayload request) {
        OutgoingTransfer transfer = outgoingTransfers.get(request.transferId());
        if (transfer == null) {
            System.out.println("[file] chunk request for unknown transfer " + request.transferId() + " - ignoring");
            return;
        }
        transfer.lastActivityEpochMs = System.currentTimeMillis();
        System.out.println("[file] chunk request received: " + request.missingChunkIndices().length + " chunk(s) requested");

        // Found alongside C-3, not named by the audit itself -- the mirror-image gap on the
        // sending side. missingChunkIndices() is exactly as wire-supplied and attacker-controlled
        // (by whoever this transfer's *receiver* is) as anything in FileOfferPayload. It was
        // already true that FileChunker.readChunk can't be tricked into reading past the real
        // source file's actual length (it bounds-checks against raf.length(), not any claimed
        // metadata) -- but rejecting an obviously-invalid index here, before ever touching disk,
        // is still strictly better than relying on that as the only line of defence, and it's a
        // three-line addition now that totalChunks is available (see registerOutgoingTransfer).
        //
        // Deliberately NOT doing here: capping the number of indices per request, or deduping
        // repeated valid indices. A receiver that repeatedly requests the same valid, in-bounds
        // index is a resource-amplification concern (real disk/CPU/network work per request), not
        // a bounds/validation one -- squarely the kind of thing Track C's own general
        // rate-limiting work should own, not a one-off special case bolted on here.
        //
        // Sent one at a time, each waiting for the previous to complete, rather than firing all
        // of them at OutboundMessageService's pool at once. Not a correctness requirement --
        // handleChunk below writes each chunk to its own byte offset, so arrival order genuinely
        // cannot corrupt the output file -- but it's the lower-risk choice given this exact
        // concurrent-send path has never been exercised against a real peer (unlike the
        // originally-proven demo logic, which only ever sent one chunk at a time by construction).
        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        for (int chunkIndex : request.missingChunkIndices()) {
            if (chunkIndex < 0 || chunkIndex >= transfer.totalChunks) {
                System.out.println("[file] ignoring out-of-range chunk request: index=" + chunkIndex
                        + " totalChunks=" + transfer.totalChunks + " transferId=" + request.transferId());
                continue;
            }
            chain = chain.thenCompose(ignored -> {
                byte[] plaintextChunk = FileChunker.readChunk(transfer.sourceFile, chunkIndex, transfer.chunkSize);
                EncryptedChunk encrypted = ChunkCipher.encrypt(transfer.fileKey, chunkIndex, plaintextChunk);
                FileChunkPayload chunkPayload = new FileChunkPayload(
                        request.transferId(), chunkIndex, encrypted.nonce(), encrypted.ciphertext());
                System.out.println("[file] sending chunk " + chunkIndex);
                return encryptAndSend.encryptAndSend(
                        transfer.targetPeerId, transfer.targetDirectMultiaddr, transfer.targetRelayMultiaddr, chunkPayload);
            });
        }
        // Also found alongside C-3: `chain` was never given an exceptionally() handler anywhere,
        // so a failure partway through (a bad read, a send failure) completed that stage
        // exceptionally and silently -- no log line, nothing. Not a security fix by itself, but
        // directly adjacent to code this fix already touches, and cheap to close at the same time.
        chain.exceptionally(ex -> {
            System.out.println("[file] chunk-send chain for transfer " + request.transferId() + " failed: " + ex);
            return null;
        });
    }

    @Override
    public void onFileChunk(PeerId sender, FileChunkPayload chunk) {
        IncomingTransfer transfer = incomingTransfers.get(chunk.transferId());
        if (transfer == null) {
            System.out.println("[file] chunk for unknown transfer " + chunk.transferId() + " - ignoring");
            return;
        }

        Path destination;
        synchronized (transfer) {
            destination = transfer.outputFile;
        }
        if (destination == null) {
            // Either genuinely unknown, or a chunk arriving for a transfer this daemon hasn't
            // (or hasn't yet) accepted -- in normal operation a peer only ever sends chunks in
            // response to a request this daemon itself sent from acceptFileTransfer, so this
            // path means a stale/unsolicited send, not something to act on.
            System.out.println("[file] chunk for not-yet-accepted transfer " + chunk.transferId() + " - ignoring");
            return;
        }

        // pre-m6h-hardening-plan.md finding C-3, the specific case it names: chunkIndex is
        // wire-supplied and was used directly in a seek() offset with no bounds check at all.
        // Checked before decrypting, not just before the seek -- cheaper to reject an obviously
        // invalid index than to spend a decrypt on it first, and there's no reason chunkIndex
        // needs the plaintext to validate.
        if (chunk.chunkIndex() < 0 || chunk.chunkIndex() >= transfer.totalChunks) {
            System.out.println("[file] rejecting chunk with out-of-range index=" + chunk.chunkIndex()
                    + " totalChunks=" + transfer.totalChunks + " transferId=" + chunk.transferId());
            return;
        }

        EncryptedChunk encrypted = new EncryptedChunk(chunk.chunkIndex(), chunk.nonce(), chunk.ciphertext());
        byte[] plaintext = ChunkCipher.decrypt(transfer.fileKey, encrypted); // throws (unchecked) on tamper -- caught by SessionManager's own outer catch, same as any other malformed inbound data

        // The other half of C-3: a chunk whose plaintext is longer than chunkSize would, without
        // this check, have overwritten the start of the *next* chunk's region on disk --
        // raf.write() at offset chunkIndex*chunkSize doesn't know or care where chunk
        // (chunkIndex+1) begins. This can't happen from an honest sender (FileChunker.readChunk
        // never returns more than chunkSize bytes) but nothing before this fix stopped a
        // malicious one from claiming a normal chunkIndex/nonce while smuggling oversized
        // plaintext in the ciphertext.
        if (plaintext.length > transfer.chunkSize) {
            System.out.println("[file] rejecting chunk " + chunk.chunkIndex() + ": decrypted length "
                    + plaintext.length + " exceeds chunkSize " + transfer.chunkSize + " transferId=" + chunk.transferId());
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(destination.toFile(), "rw")) {
            raf.seek((long) chunk.chunkIndex() * transfer.chunkSize);
            raf.write(plaintext);
        } catch (Exception e) {
            System.out.println("[file] FAILED to write chunk " + chunk.chunkIndex() + " to disk: " + e);
            return;
        }

        storage.markChunkReceived(chunk.transferId(), chunk.chunkIndex());
        List<Integer> stillMissing = storage.missingChunks(chunk.transferId(), transfer.totalChunks);
        int received = transfer.totalChunks - stillMissing.size();

        System.out.println("[file] chunk " + chunk.chunkIndex() + " received, decrypted, and written to disk ("
                + received + "/" + transfer.totalChunks + ")");
        eventListener.onFileTransferProgress(chunk.transferId(), received, transfer.totalChunks, TransferState.IN_PROGRESS);

        if (stillMissing.isEmpty()) {
            completeTransfer(chunk.transferId(), transfer);
        }
    }

    private void completeTransfer(String transferId, IncomingTransfer transfer) {
        try {
            Path destination;
            synchronized (transfer) {
                destination = transfer.outputFile;
            }
            if (destination != null && !Files.exists(destination)) {
                // Every chunk is already marked received in storage, but nothing has actually
                // been written to disk yet -- true only when accept-time found zero missing
                // chunks with no prior write ever having happened (a pathological "already fully
                // resumed, output file never created" edge case). Create an empty placeholder so
                // sha256HexOfFile below has something to hash rather than throwing.
                Files.createFile(destination);
            }
            String actualHash = destination != null ? FileChunker.sha256HexOfFile(destination) : "";
            boolean hashMatches = actualHash.equals(transfer.fileHash);
            TransferState finalState = hashMatches ? TransferState.COMPLETED : TransferState.FAILED;
            storage.updateTransferState(transferId, finalState);

            System.out.println("[file] transfer " + transferId + " complete: " + destination
                    + " - " + (hashMatches ? "hash verified" : "HASH MISMATCH, marked FAILED"));
            eventListener.onFileTransferProgress(transferId, transfer.totalChunks, transfer.totalChunks, finalState);
        } catch (Exception e) {
            System.out.println("[file] FAILED to finalize transfer " + transferId + ": " + e);
            storage.updateTransferState(transferId, TransferState.FAILED);
            eventListener.onFileTransferProgress(transferId, transfer.totalChunks, transfer.totalChunks, TransferState.FAILED);
        } finally {
            // Evict completed transfer from in-memory map to prevent unbounded memory growth.
            // Historical record remains in storage (file_transfers table).
            incomingTransfers.remove(transferId);
        }
    }

    // Deliberately a duplicate of SessionManager's own private deriveDirectConversationId, not a
    // shared reference to it -- tried sharing first (package-private access, since both classes
    // live in the same package), and reverted after discovering what it actually cost: any
    // reference to SessionManager forces this whole class to only ever compile alongside it, and
    // SessionManager.java needs libsignal-client/jvm-libp2p just to compile (SignalProtocolAddress,
    // PreKeyBundle, etc., used throughout it) -- neither of which this class otherwise needs at
    // all. That dependency would have quietly cost this milestone real, executed verification of
    // its single most substantial piece (accept-gate, resume-via-missingChunks, hash
    // verification) for the sake of not duplicating four lines of pure, static string logic that
    // is exceptionally unlikely to ever need to change independently in the two places it lives.
    // Found by actually attempting the standalone compile, not decided by reasoning about it in
    // the abstract -- see SessionManager's own comment on its reverted visibility change for the
    // other half of this story.
    private static String deriveDirectConversationId(String peerIdA, String peerIdB) {
        return peerIdA.compareTo(peerIdB) <= 0
                ? "direct-" + peerIdA + "-" + peerIdB
                : "direct-" + peerIdB + "-" + peerIdA;
    }

    /** What this node needs to answer a chunk request for a transfer it initiated via {@code sendFile}. */
    private static final class OutgoingTransfer {
        final Path sourceFile;
        final FileKey fileKey;
        final int chunkSize;
        final int totalChunks; // computed from the real source file's size -- see registerOutgoingTransfer
        final PeerId targetPeerId;
        final String targetDirectMultiaddr;
        final String targetRelayMultiaddr;
        final long createdAtEpochMs;
        volatile long lastActivityEpochMs;

        OutgoingTransfer(Path sourceFile, FileKey fileKey, int chunkSize, int totalChunks, PeerId targetPeerId,
                          String targetDirectMultiaddr, String targetRelayMultiaddr) {
            this.sourceFile = sourceFile;
            this.fileKey = fileKey;
            this.chunkSize = chunkSize;
            this.totalChunks = totalChunks;
            this.targetPeerId = targetPeerId;
            this.targetDirectMultiaddr = targetDirectMultiaddr;
            this.targetRelayMultiaddr = targetRelayMultiaddr;
            this.createdAtEpochMs = System.currentTimeMillis();
            this.lastActivityEpochMs = this.createdAtEpochMs;
        }
    }

    /** What this node needs to receive (or decide whether to accept) a transfer offered to it. */
    private static final class IncomingTransfer {
        final PeerId senderPeerId;
        final String senderAddress;
        final String fileName;
        final String fileHash;
        final int totalChunks;
        final int chunkSize;
        final FileKey fileKey;
        volatile Path outputFile; // null until acceptFileTransfer is called

        IncomingTransfer(PeerId senderPeerId, String senderAddress, String fileName, String fileHash,
                          int totalChunks, int chunkSize, FileKey fileKey) {
            this.senderPeerId = senderPeerId;
            this.senderAddress = senderAddress;
            this.fileName = fileName;
            this.fileHash = fileHash;
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.fileKey = fileKey;
        }
    }
}
