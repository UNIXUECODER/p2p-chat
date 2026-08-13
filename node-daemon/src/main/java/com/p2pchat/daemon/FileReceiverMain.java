package com.p2pchat.daemon;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.PreKeyBundleFactory;
import com.p2pchat.crypto.SecureSessionService;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.filetransfer.ChunkCipher;
import com.p2pchat.filetransfer.EncryptedChunk;
import com.p2pchat.filetransfer.FileChunker;
import com.p2pchat.filetransfer.FileKey;
import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.filetransfer.wire.FileTransferMessageCodec;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.TransferState;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M4c/M4d: the receiving side of a real, single-peer, resumable file transfer over an
 * encrypted connection. The first milestone where a peer has to be BOTH a passive listener
 * (SecureListenerMain's M2c role: publish a bundle, wait, decrypt the first PreKey message)
 * AND an active sender in the same process (reply with a FileChunkRequestPayload once the
 * offer arrives).
 *
 * Verified this is architecturally sound before writing it — see the M4c section of README.md:
 * {@code LibsignalSecureSessionService} holds one {@code SignalProtocolStore} for the whole
 * service lifetime, so a later {@code encrypt()} call correctly finds the session an earlier
 * {@code decrypt()} established; {@code PeerNetworkService.sendEnvelope} is symmetric, available
 * to any node with a running host regardless of who dialed first.
 *
 * <p><b>Does not take the sender's address as a startup argument</b> — an earlier version did,
 * which created a real chicken-and-egg bug caught on the first actual test run: the documented
 * workflow is "start the receiver first," but that's exactly when the sender's address can't be
 * known yet. The fix is in {@code FileOfferPayload}: the sender reports its own address inside
 * the encrypted offer, so this class learns where to reply from the authenticated message
 * itself, not from something the operator has to guess before the sender exists.
 *
 * <p><b>M4d — storage-backed resume:</b> each chunk is written directly to its correct byte
 * offset in the output file as it arrives (not accumulated in memory first) and marked received
 * in {@code core-storage} immediately afterward. "Which chunks are missing" is computed from
 * storage, not an in-memory counter — so it survives a restart: knowing you're missing chunks
 * 3 and 7 is only useful if the bytes for the chunks you're NOT missing are actually durably on
 * disk already, which is why the write-to-correct-offset happens before the storage write, not
 * instead of it. {@code saveFileMetadata} had to become an upsert (see {@code SqliteStorageService})
 * because {@code file_chunk_state} has a foreign key on the row it creates, and a resumed
 * transfer legitimately re-establishes that row.
 */
public class FileReceiverMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;
        // Optional, for testing M4d's resume behavior end-to-end: after successfully writing
        // this chunk index to disk and marking it received, exit immediately (simulating a
        // crash) instead of continuing to wait. Restart with the same -Pdatadir and the same
        // sender re-offering, and only the chunks still missing should be re-requested.
        int exitAfterChunk = args.length > 1 ? Integer.parseInt(args[1]) : -1;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));

        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        SignalIdentity signalIdentity = SignalIdentityVault.loadOrCreate(baseDir);
        InMemorySignalProtocolStore signalStore =
                new InMemorySignalProtocolStore(signalIdentity.keyPair(), signalIdentity.registrationId());
        SignalProtocolAddress localSignalAddress = new SignalProtocolAddress(identity.peerId(), 1);
        SecureSessionService sessions = new LibsignalSecureSessionService(signalStore, localSignalAddress);

        PreKeyBundle bundle = PreKeyBundleFactory.create(signalStore);
        String bundleBase64 = Base64.getEncoder().encodeToString(PreKeyBundleCodec.encode(bundle));
        Path bundleFile = baseDir.resolve("published-bundle.b64");
        Files.writeString(bundleFile, bundleBase64);

        // Opened against the same data dir as everything else, so it's the same SQLite file
        // across restarts — that persistence is the entire point of M4d.
        SqliteDatabase database = SqliteDatabase.openOrCreate(baseDir);
        StorageService storage = new SqliteStorageService(database);

        // Keyed by transferId. Only holds what's needed to serve THIS process's live callback
        // (fileKey, output path, chunk size) — completion state itself lives in storage, not
        // here, since this map does not survive a restart but storage does.
        Map<String, ReceivingTransfer> transfers = new ConcurrentHashMap<>();

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            try {
                // Built fresh from the actual connecting peer each call, same as
                // SecureListenerMain.
                SignalProtocolAddress remote = new SignalProtocolAddress(sender.value(), 1);

                EncryptedFrame frame = EncryptedFrameCodec.decode(data);
                byte[] plaintext = sessions.decrypt(remote, frame);
                FileTransferMessage message = FileTransferMessageCodec.decode(plaintext);

                if (message instanceof FileOfferPayload offer) {
                    handleOffer(offer, sender.value(), network, sessions, remote, transfers, storage, baseDir);

                } else if (message instanceof FileChunkPayload chunkPayload) {
                    handleChunk(chunkPayload, transfers, storage, exitAfterChunk);

                } else {
                    System.out.println("[file] unexpected message type from " + sender + ": " + message);
                }
            } catch (Exception e) {
                System.out.println("[file] FAILED to process message from " + sender + ": " + e);
            }
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Data dir     : " + baseDir);
        System.out.println();
        System.out.println("Give the sender BOTH of these:");
        System.out.println();
        System.out.println("1) Network address:");
        for (String addr : network.listenAddresses()) {
            System.out.println("   " + addr);
        }
        System.out.println();
        System.out.println("2) Pre-key bundle file (changes every restart - always use the latest):");
        System.out.println("   " + bundleFile);
        System.out.println();
        System.out.println("That's everything the sender needs. This receiver does not need to know the");
        System.out.println("sender's address in advance - the sender reports its own address inside the");
        System.out.println("encrypted offer, so this can safely be the very first thing you start.");
        System.out.println();
        System.out.println("Chunk progress for any transfer is persisted in " + baseDir.resolve("p2p-chat.sqlite") + " -");
        System.out.println("if this process is restarted with the same data dir mid-transfer, re-sending the");
        System.out.println("same offer will only request the chunks still missing, not start over.");
        System.out.println();
        System.out.println("Waiting for a file offer. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    private static void handleOffer(FileOfferPayload offer, String senderPeerId, PeerNetworkService network,
                                     SecureSessionService sessions, SignalProtocolAddress remote,
                                     Map<String, ReceivingTransfer> transfers, StorageService storage,
                                     Path baseDir) throws Exception {
        Path outputFile = baseDir.resolve("received-" + offer.fileName());
        ReceivingTransfer transfer = new ReceivingTransfer(
                offer.fileName(), offer.fileHash(), offer.totalChunks(), offer.chunkSize(),
                FileKey.fromBytes(offer.fileKey()), outputFile);
        transfers.put(offer.transferId(), transfer);

        // No real conversation concept exists yet (M5) — "direct-<peer>" is a placeholder
        // identifying the 1:1 channel this transfer arrived on. See core-filetransfer's
        // FileOfferPayload Javadoc for the same reasoning applied to the wire format.
        FileTransfer transferRecord = new FileTransfer(
                offer.transferId(), "direct-" + senderPeerId, offer.fileName(), offer.fileSize(),
                offer.fileHash(), offer.chunkSize(), offer.totalChunks(), TransferState.IN_PROGRESS,
                outputFile.toString(), System.currentTimeMillis());
        storage.saveFileMetadata(transferRecord);

        List<Integer> missing = storage.missingChunks(offer.transferId(), offer.totalChunks());
        boolean resuming = missing.size() < offer.totalChunks();
        System.out.println("[file] offer received: \"" + offer.fileName() + "\" ("
                + offer.fileSize() + " bytes, " + offer.totalChunks() + " chunks) - "
                + (resuming
                        ? "resuming: " + missing.size() + " chunk(s) still needed"
                        : "requesting all " + missing.size() + " chunk(s)"));

        if (missing.isEmpty()) {
            completeTransfer(transfer, storage);
            return;
        }

        int[] missingArray = new int[missing.size()];
        for (int i = 0; i < missing.size(); i++) {
            missingArray[i] = missing.get(i);
        }
        FileChunkRequestPayload request = new FileChunkRequestPayload(offer.transferId(), missingArray);
        sendMessage(network, sessions, offer.senderAddress(), remote, request);
    }

    private static void handleChunk(FileChunkPayload chunkPayload, Map<String, ReceivingTransfer> transfers,
                                     StorageService storage, int exitAfterChunk) {
        ReceivingTransfer transfer = transfers.get(chunkPayload.transferId());
        if (transfer == null) {
            System.out.println("[file] chunk for unknown transfer " + chunkPayload.transferId() + " - ignoring");
            return;
        }

        EncryptedChunk encrypted = new EncryptedChunk(chunkPayload.chunkIndex(), chunkPayload.nonce(), chunkPayload.ciphertext());
        byte[] plaintext = ChunkCipher.decrypt(transfer.fileKey, encrypted);

        // Written directly to the correct offset in the real output file, not held in memory —
        // this is what makes the bytes actually resumable, not just "not re-requested". A
        // fresh RandomAccessFile per chunk is simple and correct; this is a demo, not a
        // performance-sensitive hot path.
        try (RandomAccessFile raf = new RandomAccessFile(transfer.outputFile.toFile(), "rw")) {
            raf.seek((long) chunkPayload.chunkIndex() * transfer.chunkSize);
            raf.write(plaintext);
        } catch (Exception e) {
            System.out.println("[file] FAILED to write chunk " + chunkPayload.chunkIndex() + " to disk: " + e);
            return;
        }

        storage.markChunkReceived(chunkPayload.transferId(), chunkPayload.chunkIndex());
        List<Integer> stillMissing = storage.missingChunks(chunkPayload.transferId(), transfer.totalChunks);

        System.out.println("[file] chunk " + chunkPayload.chunkIndex() + " received, decrypted, and written to disk ("
                + (transfer.totalChunks - stillMissing.size()) + "/" + transfer.totalChunks + ")");

        if (exitAfterChunk == chunkPayload.chunkIndex()) {
            System.out.println();
            System.out.println("Simulating a crash after chunk " + exitAfterChunk + " (-Pexitafter was set).");
            System.out.println("The chunk above is already durably written and marked received in storage.");
            System.out.println("Restart with the same -Pdatadir and have the sender re-offer the same file to resume.");
            System.exit(1);
        }

        if (stillMissing.isEmpty()) {
            completeTransfer(transfer, storage);
        }
    }

    private static void completeTransfer(ReceivingTransfer transfer, StorageService storage) {
        try {
            String actualHash = FileChunker.sha256HexOfFile(transfer.outputFile);
            boolean hashMatches = actualHash.equals(transfer.fileHash);

            System.out.println();
            System.out.println("All chunks accounted for. File complete: " + transfer.outputFile);
            System.out.println("Expected SHA-256: " + transfer.fileHash);
            System.out.println("Actual SHA-256:   " + actualHash);
            System.out.println(hashMatches
                    ? "M4d CONFIRMED: file received via storage-backed, resumable chunk tracking, and verified correctly."
                    : "M4d FAILED: reassembled file does not match the expected hash.");
        } catch (Exception e) {
            System.out.println("[file] FAILED to finalize transfer: " + e);
        }
    }

    private static void sendMessage(PeerNetworkService network, SecureSessionService sessions,
                                     String targetAddress, SignalProtocolAddress remote,
                                     FileTransferMessage message) throws Exception {
        byte[] plaintext = FileTransferMessageCodec.encode(message);
        EncryptedFrame frame = sessions.encrypt(remote, plaintext);
        network.sendEnvelope(targetAddress, EncryptedFrameCodec.encode(frame));
    }

    /** What this process needs to serve the live callback for one transfer. Completion state itself lives in storage, not here. */
    private static final class ReceivingTransfer {
        final String fileName;
        final String fileHash;
        final int totalChunks;
        final int chunkSize;
        final FileKey fileKey;
        final Path outputFile;

        ReceivingTransfer(String fileName, String fileHash, int totalChunks, int chunkSize, FileKey fileKey, Path outputFile) {
            this.fileName = fileName;
            this.fileHash = fileHash;
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.fileKey = fileKey;
            this.outputFile = outputFile;
        }
    }
}
