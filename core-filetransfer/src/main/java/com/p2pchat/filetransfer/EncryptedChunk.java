package com.p2pchat.filetransfer;

/**
 * One encrypted chunk, matching the ciphertext/nonce shape of {@code FileChunkPayload}
 * (docs/architecture-spec.md §6's .proto sketch). {@code transferId} is deliberately not a
 * field here — that's orchestration-level identity (M4b), not something a single encrypt/decrypt
 * call needs to know about.
 */
public record EncryptedChunk(int chunkIndex, byte[] nonce, byte[] ciphertext) {
}
