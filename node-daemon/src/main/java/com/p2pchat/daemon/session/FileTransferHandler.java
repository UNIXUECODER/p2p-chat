package com.p2pchat.daemon.session;

import com.p2pchat.filetransfer.FileKey;
import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.ConnectivityStatus;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * M6e-2: what {@link SessionManager} calls when a decrypted, dispatched message turns out to be
 * file-transfer traffic rather than chat.
 *
 * <p><b>Deliberately deferred, not silently skipped — through M6e-2/M6g-2.</b> {@code
 * FileReceiverMain}/{@code FileSenderMain} (M4c/M4d) already prove a real, resumable, chunk-level
 * transfer works — but that logic (offer/accept negotiation, chunk request/response looping,
 * writing each chunk to disk at the correct offset, {@code missingChunks}-driven resume) was
 * genuinely substantial, and folding all of it into {@link SessionManager} then would have made
 * an already-large milestone larger still for a capability outside that milestone's own stated
 * scope. Default no-op methods on this interface meant file-transfer support could be added
 * later as a self-contained implementation, not a {@code SessionManager} rewrite — the same
 * reasoning {@code WebSocketTextHandler} (M6d) already applied to its own optional callbacks.
 * {@code DefaultFileTransferHandler} (M6g-3) is that later implementation.
 *
 * <p><b>{@link #attach}, {@link #registerOutgoingTransfer}, and {@link #acceptFileTransfer}
 * added in M6g-3</b>, alongside {@link SessionManager#sendFile} and {@link
 * SessionManager#acceptFileTransfer} — the interface only ever needed to react to <i>inbound</i>
 * file-transfer messages before; a real implementation also needs a way to actually reply
 * (encrypt + send, hence {@link #attach}), a way to learn about a transfer <i>this node
 * initiated</i> (hence {@link #registerOutgoingTransfer} — {@link SessionManager#sendFile} calls
 * this, not the demo Mains' pattern of keeping that bookkeeping inline in a single-transfer
 * process), and a way for a caller to actually accept a pending offer (hence {@link
 * #acceptFileTransfer} — see that method's own Javadoc for why this is a genuine behavioral
 * addition over {@code FileReceiverMain}'s proven logic, not just a rename of it). All three are
 * default no-ops for the same reason the original three methods are: a test's anonymous {@code
 * new FileTransferHandler() {}} (see {@code SessionManagerReceivePipelineTest}) still compiles
 * and behaves correctly without implementing any of them.
 */
public interface FileTransferHandler {

    /**
     * How a real implementation actually replies to a peer — encrypting {@code message} for
     * {@code targetPeerId} and sending the result, direct-then-relay. A functional interface
     * rather than handing over {@code SecureSessionService}/{@code OutboundMessageService}
     * directly, for the same reason {@code ContactService.DiscoveryLookup} (M6g-2) is a
     * functional interface rather than a direct {@code DiscoveryController} dependency: it lets
     * {@code DefaultFileTransferHandler}'s own real chunk-negotiation logic — genuinely the
     * meaty part of this milestone — be tested with a fake implementation of this one narrow
     * seam, rather than needing a live libp2p host and a real Signal session to prove correct.
     * As a consequence, neither this interface nor {@code DefaultFileTransferHandler} needs
     * {@code libsignal-client} or {@code jvm-libp2p} to compile — a nice side effect of choosing
     * the right seam for testability, not the reason it was chosen.
     */
    @FunctionalInterface
    interface EncryptAndSend {
        CompletableFuture<ConnectivityStatus> encryptAndSend(
                PeerId targetPeerId, String directMultiaddr, String relayMultiaddr, FileTransferMessage message);
    }

    /**
     * Called once, by {@link SessionManager#start}, as soon as this node's own identity-dependent
     * state (which {@link #encryptAndSend} above is built from) actually exists — mirrors {@code
     * SessionManager}'s own two-phase constructed-then-started lifecycle, for the same reason:
     * {@code ownPeerId}/{@code ownAddress} aren't known until {@code network.start()} has
     * returned, but this handler is constructed (and handed to {@code SessionManager}'s own
     * constructor) before that.
     */
    default void attach(EncryptAndSend encryptAndSend, PeerId ownPeerId, String ownAddress) {
    }

    /**
     * Called by {@link SessionManager#sendFile} right after a file offer is sent, so that a
     * {@link FileChunkRequestPayload} arriving later for {@code transferId} can be answered — a
     * real implementation needs {@code sourceFile}/{@code fileKey}/{@code chunkSize} to read and
     * re-encrypt the requested chunks, and the target's address to reply to, none of which the
     * inbound {@code onFileChunkRequest} callback alone carries.
     */
    default void registerOutgoingTransfer(String transferId, Path sourceFile, FileKey fileKey, int chunkSize,
                                           PeerId targetPeerId, String targetDirectMultiaddr, String targetRelayMultiaddr) {
    }

    /**
     * Called by {@link SessionManager#acceptFileTransfer} once a caller has decided to accept a
     * previously-offered transfer and chosen where to save it. A no-op (not an error) if {@code
     * transferId} isn't a known, pending offer — matches this project's established convention
     * for "acting on an id that may not exist" (see {@code StorageService.updateDeliveryState}).
     */
    default void acceptFileTransfer(String transferId, Path savePath) {
    }

    default void onFileOffer(PeerId sender, FileOfferPayload offer) {
    }

    default void onFileChunkRequest(PeerId sender, FileChunkRequestPayload request) {
    }

    default void onFileChunk(PeerId sender, FileChunkPayload chunk) {
    }
}

