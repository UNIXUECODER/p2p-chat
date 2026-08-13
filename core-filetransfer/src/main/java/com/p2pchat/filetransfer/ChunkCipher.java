package com.p2pchat.filetransfer;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Per-chunk AES-256-GCM encryption, per docs/architecture-spec.md §12 step 2 and §8. A fresh
 * random 12-byte nonce is generated for every chunk (a nonce must never repeat under the same
 * key, which is why it's generated per call rather than derived from, say, chunk index alone —
 * a bug in a deterministic derivation would be a silent, catastrophic GCM failure, whereas a
 * random nonce is safe as long as {@link SecureRandom} is doing its job). The nonce is carried
 * alongside the ciphertext in {@link EncryptedChunk}, matching {@code FileChunkPayload}'s
 * explicit {@code nonce} field in the spec's own .proto sketch.
 *
 * <p>Verified by actually running this against real data (see the M4a section of README.md) —
 * including a deliberate tamper test (flip one ciphertext bit, confirm decrypt throws) proving
 * GCM's authentication actually catches corruption rather than assuming it does.
 */
public final class ChunkCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private ChunkCipher() {
    }

    public static EncryptedChunk encrypt(FileKey key, int chunkIndex, byte[] plaintextChunk) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintextChunk);

            return new EncryptedChunk(chunkIndex, nonce, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt chunk " + chunkIndex, e);
        }
    }

    /** Throws (unchecked) if the ciphertext or nonce has been corrupted or tampered with — GCM's authentication tag check fails closed. */
    public static byte[] decrypt(FileKey key, EncryptedChunk chunk) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunk.nonce()));
            return cipher.doFinal(chunk.ciphertext());
        } catch (AEADBadTagException e) {
            throw new RuntimeException("Chunk " + chunk.chunkIndex() + " failed authentication "
                    + "(wrong key, corrupted ciphertext, or tampering)", e);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decrypt chunk " + chunk.chunkIndex(), e);
        }
    }
}
