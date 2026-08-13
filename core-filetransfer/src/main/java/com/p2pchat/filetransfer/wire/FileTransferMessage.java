package com.p2pchat.filetransfer.wire;

/**
 * The three file-transfer message kinds that travel as the plaintext of an {@code EncryptedFrame}
 * once decrypted via core-crypto's {@code SecureSessionService} — the same "arbitrary application
 * bytes" role core-network's {@code EnvelopeProtocol} has carried since M2b, just with real
 * structure this time instead of a raw test string.
 *
 * <p>Reuses the exact marker values docs/architecture-spec.md §6's {@code EnvelopeType} enum
 * already assigned ({@code FILE_OFFER=6}, {@code FILE_CHUNK_REQUEST=7}, {@code FILE_CHUNK=8}) —
 * see {@link FileTransferMessageCodec} — so nothing needs renumbering if/when M5 unifies this
 * into a shared dispatch mechanism across message kinds. That extraction is deliberately not
 * done here: there is exactly one consumer of this concept right now (file transfer), and
 * generalizing for a second consumer (chat, in M5) before it exists would be guessing at a
 * shape it hasn't earned yet.
 */
public sealed interface FileTransferMessage
        permits FileOfferPayload, FileChunkRequestPayload, FileChunkPayload {
}
