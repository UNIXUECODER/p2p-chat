package com.p2pchat.filetransfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Splits a file into fixed-size chunks and computes SHA-256 hashes, per
 * docs/architecture-spec.md §12 steps 1 and 5. Default chunk size matches the spec's default
 * (256 KB); tunable per the spec's own "tune later" note.
 */
public final class FileChunker {

    public static final int DEFAULT_CHUNK_SIZE_BYTES = 256 * 1024;

    private FileChunker() {
    }

    /** Number of chunks a file of {@code fileSize} bytes splits into at {@code chunkSize} bytes per chunk (the last chunk may be shorter). */
    public static int chunkCount(long fileSize, int chunkSize) {
        if (fileSize == 0) {
            return 0;
        }
        return (int) Math.ceilDiv(fileSize, chunkSize);
    }

    /** Reads exactly chunk {@code chunkIndex} (0-based) from {@code file}, sized {@code chunkSize} except possibly the last chunk. */
    public static byte[] readChunk(Path file, int chunkIndex, int chunkSize) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long offset = (long) chunkIndex * chunkSize;
            long remaining = raf.length() - offset;
            if (remaining <= 0) {
                throw new IllegalArgumentException("chunkIndex " + chunkIndex + " is past the end of " + file);
            }
            int thisChunkSize = (int) Math.min(chunkSize, remaining);
            byte[] buffer = new byte[thisChunkSize];
            raf.seek(offset);
            raf.readFully(buffer);
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read chunk " + chunkIndex + " from " + file, e);
        }
    }

    /** SHA-256 of a single chunk (or any byte array), lowercase hex. Used for content-addressing (spec §12 step 4). */
    public static String sha256Hex(byte[] data) {
        return toHex(sha256Digest().digest(data));
    }

    /** SHA-256 of an entire file's contents, streamed rather than loaded fully into memory. Used for spec §12 step 5's completion check. */
    public static String sha256HexOfFile(Path file) {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (IOException e) {
            throw new RuntimeException("Failed to hash file " + file, e);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e); // every JDK includes it
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
