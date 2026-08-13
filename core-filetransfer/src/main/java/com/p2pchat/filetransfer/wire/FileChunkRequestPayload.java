package com.p2pchat.filetransfer.wire;

/**
 * Sent by the recipient to ask for specific chunks. Not defined anywhere in
 * docs/architecture-spec.md's §6 {@code .proto} sketch — {@code EnvelopeType.FILE_CHUNK_REQUEST}
 * exists there, but no corresponding payload message does. This fills that gap, following §12
 * step 3's description of the behavior: "a fresh request just skips chunks already marked
 * received" — which is exactly what re-sending this with a shorter {@code missingChunkIndices}
 * achieves. An empty array means "I already have everything."
 */
public record FileChunkRequestPayload(
        String transferId,
        int[] missingChunkIndices
) implements FileTransferMessage {
}
