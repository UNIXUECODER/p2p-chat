package com.p2pchat.daemon.session;

import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.model.PeerId;

/**
 * M6e-2: what {@link SessionManager} calls when a decrypted, dispatched message turns out to be
 * file-transfer traffic rather than chat.
 *
 * <p><b>Deliberately deferred, not silently skipped.</b> {@code FileReceiverMain}/{@code
 * FileSenderMain} (M4c/M4d) already prove a real, resumable, chunk-level transfer works — but
 * that logic (offer/accept negotiation, chunk request/response looping, writing each chunk to
 * disk at the correct offset, {@code missingChunks}-driven resume) is genuinely substantial, and
 * folding all of it into {@link SessionManager} now would make an already-large milestone
 * (M6a/M6b/M6e-1 combined into one live daemon core) larger still for a capability M6e-2's own
 * scope was never named as covering. Default no-op methods here mean file-transfer support can
 * be added later as a self-contained implementation of this interface, not a {@code
 * SessionManager} rewrite — the same reasoning {@code WebSocketTextHandler} (M6d) already
 * applied to its own optional callbacks.
 */
public interface FileTransferHandler {

    default void onFileOffer(PeerId sender, FileOfferPayload offer) {
    }

    default void onFileChunkRequest(PeerId sender, FileChunkRequestPayload request) {
    }

    default void onFileChunk(PeerId sender, FileChunkPayload chunk) {
    }
}
