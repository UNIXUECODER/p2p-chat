package com.p2pchat.daemon;

import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.filetransfer.wire.FileTransferMessageCodec;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * M4b: encode/decode round-trip checks for the three file-transfer wire payloads
 * (FileOfferPayload, FileChunkRequestPayload, FileChunkPayload), proven in isolation before
 * any of it is wired to a real connection — same reasoning as M3a's RelayFrameCodec/
 * DiscoveryFrameCodec, which their own comments describe as "verified standalone... before
 * being wired into RelayProtocol/DiscoveryProtocol".
 *
 * Covers normal cases, a Unicode filename, empty-array/empty-ciphertext edge cases, and two
 * "malformed input correctly rejected" checks (wrong-length file key, wrong-length nonce).
 */
public class WireCodecDemoMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testFileOffer();
        testFileOfferUnicodeFileName();
        testFileChunkRequest();
        testFileChunkRequestEmpty();
        testFileChunk();
        testFileChunkEmptyCiphertext();
        testWrongFileKeyLengthRejected();
        testWrongNonceLengthRejected();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.out.println();
        System.out.println(failed == 0
                ? "M4b CONFIRMED: all three file-transfer wire payloads encode/decode correctly, including edge cases."
                : "M4b FAILED: see the [FAIL] lines above.");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testFileOffer() {
        byte[] fileKey = randomBytes(32);
        FileOfferPayload original = new FileOfferPayload(
                "t_abc123", "/ip4/127.0.0.1/tcp/9000/p2p/16Uiu2HAmDemoSenderPeerId", "demo-file.pdf", 123456789L,
                "deadbeef".repeat(8), 262144, 5, fileKey);

        byte[] wire = FileTransferMessageCodec.encode(original);
        FileTransferMessage decoded = FileTransferMessageCodec.decode(wire);

        check("FileOffer decodes to correct type", decoded instanceof FileOfferPayload);
        FileOfferPayload result = (FileOfferPayload) decoded;
        check("FileOffer.transferId", result.transferId().equals(original.transferId()));
        check("FileOffer.senderAddress", result.senderAddress().equals(original.senderAddress()));
        check("FileOffer.fileName", result.fileName().equals(original.fileName()));
        check("FileOffer.fileSize", result.fileSize() == original.fileSize());
        check("FileOffer.fileHash", result.fileHash().equals(original.fileHash()));
        check("FileOffer.chunkSize", result.chunkSize() == original.chunkSize());
        check("FileOffer.totalChunks", result.totalChunks() == original.totalChunks());
        check("FileOffer.fileKey bytes", Arrays.equals(result.fileKey(), original.fileKey()));
    }

    private static void testFileOfferUnicodeFileName() {
        FileOfferPayload original = new FileOfferPayload(
                "t_unicode", "/ip4/127.0.0.1/tcp/9000/p2p/16Uiu2HAmDemoSenderPeerId",
                "\u65e5\u672c\u8a9e\u30d5\u30a1\u30a4\u30eb.txt", 42L,
                "ab".repeat(32), 1024, 1, randomBytes(32));

        byte[] wire = FileTransferMessageCodec.encode(original);
        FileOfferPayload result = (FileOfferPayload) FileTransferMessageCodec.decode(wire);
        check("FileOffer Unicode filename round-trips", result.fileName().equals(original.fileName()));
    }

    private static void testFileChunkRequest() {
        FileChunkRequestPayload original = new FileChunkRequestPayload("t_abc123", new int[]{0, 3, 7, 99, 1000});
        byte[] wire = FileTransferMessageCodec.encode(original);
        FileTransferMessage decoded = FileTransferMessageCodec.decode(wire);

        check("FileChunkRequest decodes to correct type", decoded instanceof FileChunkRequestPayload);
        FileChunkRequestPayload result = (FileChunkRequestPayload) decoded;
        check("FileChunkRequest.transferId", result.transferId().equals(original.transferId()));
        check("FileChunkRequest.missingChunkIndices", Arrays.equals(result.missingChunkIndices(), original.missingChunkIndices()));
    }

    private static void testFileChunkRequestEmpty() {
        // "No chunks missing" — the natural way a resume-check reports "you already have everything" (M4d).
        FileChunkRequestPayload original = new FileChunkRequestPayload("t_done", new int[]{});
        byte[] wire = FileTransferMessageCodec.encode(original);
        FileChunkRequestPayload result = (FileChunkRequestPayload) FileTransferMessageCodec.decode(wire);
        check("FileChunkRequest empty array round-trips", result.missingChunkIndices().length == 0);
    }

    private static void testFileChunk() {
        FileChunkPayload original = new FileChunkPayload("t_abc123", 42, randomBytes(12), randomBytes(1000));
        byte[] wire = FileTransferMessageCodec.encode(original);
        FileTransferMessage decoded = FileTransferMessageCodec.decode(wire);

        check("FileChunk decodes to correct type", decoded instanceof FileChunkPayload);
        FileChunkPayload result = (FileChunkPayload) decoded;
        check("FileChunk.transferId", result.transferId().equals(original.transferId()));
        check("FileChunk.chunkIndex", result.chunkIndex() == original.chunkIndex());
        check("FileChunk.nonce bytes", Arrays.equals(result.nonce(), original.nonce()));
        check("FileChunk.ciphertext bytes", Arrays.equals(result.ciphertext(), original.ciphertext()));
    }

    private static void testFileChunkEmptyCiphertext() {
        // Degenerate but legal: the codec itself should not choke on a zero-length ciphertext.
        FileChunkPayload original = new FileChunkPayload("t_edge", 0, randomBytes(12), new byte[0]);
        byte[] wire = FileTransferMessageCodec.encode(original);
        FileChunkPayload result = (FileChunkPayload) FileTransferMessageCodec.decode(wire);
        check("FileChunk empty ciphertext round-trips", result.ciphertext().length == 0);
    }

    private static void testWrongFileKeyLengthRejected() {
        FileOfferPayload badOffer = new FileOfferPayload(
                "t_bad", "/ip4/127.0.0.1/tcp/9000/p2p/16Uiu2HAmDemoSenderPeerId", "x.txt", 1L, "ab", 1024, 1, randomBytes(16));
        boolean threw = false;
        try {
            FileTransferMessageCodec.encode(badOffer);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Wrong-length fileKey rejected at encode time", threw);
    }

    private static void testWrongNonceLengthRejected() {
        FileChunkPayload badChunk = new FileChunkPayload("t_bad", 0, randomBytes(8), randomBytes(10));
        boolean threw = false;
        try {
            FileTransferMessageCodec.encode(badChunk);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Wrong-length nonce rejected at encode time", threw);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + description);
        } else {
            failed++;
            System.out.println("[FAIL] " + description);
        }
    }
}
