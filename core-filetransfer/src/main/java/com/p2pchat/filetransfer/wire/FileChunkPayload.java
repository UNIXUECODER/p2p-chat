package com.p2pchat.filetransfer.wire;

/**
 * One chunk, sent in response to a {@link FileChunkRequestPayload}. Directly mirrors
 * {@code core.filetransfer.EncryptedChunk} (chunkIndex/nonce/ciphertext), plus the
 * {@code transferId} needed to route it to the right in-progress transfer on the receiving end
 * — a single field is the only difference between this and {@code EncryptedChunk}, since that
 * type deliberately has no orchestration-level identity of its own (see its Javadoc).
 */
public record FileChunkPayload(
        String transferId,
        int chunkIndex,
        byte[] nonce,
        byte[] ciphertext
) implements FileTransferMessage {
}
