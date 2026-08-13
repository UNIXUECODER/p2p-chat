package com.p2pchat.filetransfer;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * A random AES-256 key used to encrypt one file's chunks. Per docs/architecture-spec.md §12
 * step 2 and §8: "each file gets a fresh random AES-256-GCM key... that key (not the file) is
 * wrapped individually per-recipient via their session." That per-recipient wrapping (via the
 * Double Ratchet session core-crypto already implements) is M4b's job — this type just models
 * the key itself, for the isolated chunking/encryption proof in M4a.
 *
 * A plain final class rather than a record on purpose: a record's auto-generated accessor would
 * hand out the backing array directly, and its default toString/equals would operate on key
 * material. {@link #bytes()} returns a defensive copy instead, and {@link #toString()} is
 * overridden to avoid printing anything sensitive.
 */
public final class FileKey {

    public static final int KEY_LENGTH_BYTES = 32; // AES-256

    private final byte[] bytes;

    private FileKey(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Generates a fresh random key using a {@link SecureRandom} instance. */
    public static FileKey generate() {
        byte[] bytes = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(bytes);
        return new FileKey(bytes);
    }

    /** Wraps existing key bytes (e.g. after unwrapping a received {@code wrapped_file_key} in M4b) — copies defensively. */
    public static FileKey fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "key bytes must not be null");
        if (bytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("FileKey must be " + KEY_LENGTH_BYTES + " bytes, got " + bytes.length);
        }
        return new FileKey(Arrays.copyOf(bytes, bytes.length));
    }

    /** A defensive copy — callers cannot mutate this key's internal state through the returned array. */
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public String toString() {
        return "FileKey[redacted]";
    }
}
