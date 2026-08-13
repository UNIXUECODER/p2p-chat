package com.p2pchat.daemon;

import com.p2pchat.filetransfer.ChunkCipher;
import com.p2pchat.filetransfer.EncryptedChunk;
import com.p2pchat.filetransfer.FileChunker;
import com.p2pchat.filetransfer.FileKey;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M4a: chunking + per-chunk AES-256-GCM encryption, proven in isolation — no networking, no
 * crypto sessions, no storage. Mirrors CryptoDemoMain's (M2a) pattern: prove the new primitive
 * works correctly before wiring it to anything else. Implements docs/architecture-spec.md §12
 * steps 1, 2, and 5 (chunking, per-chunk encryption, whole-file integrity check).
 *
 * Uses a deliberately small chunk size (16 bytes) so a short demo file produces several chunks —
 * makes the multi-chunk reassembly path and per-chunk output actually visible in the console,
 * rather than everything fitting in one chunk.
 */
public class FileTransferDemoMain {

    public static void main(String[] args) throws Exception {
        int chunkSize = 16;

        Path tempFile = Files.createTempFile("p2p-chat-m4a-demo", ".txt");
        String originalContent = "This is a small demo file for the M4a file-chunking-and-encryption test. "
                + "It is deliberately larger than one chunk so multiple chunks get exercised.";
        Files.writeString(tempFile, originalContent, StandardCharsets.UTF_8);

        try {
            long fileSize = Files.size(tempFile);
            int totalChunks = FileChunker.chunkCount(fileSize, chunkSize);
            String originalFileHash = FileChunker.sha256HexOfFile(tempFile);

            System.out.println("Demo file: " + tempFile);
            System.out.println("Size: " + fileSize + " bytes, chunk size: " + chunkSize
                    + " bytes, total chunks: " + totalChunks);
            System.out.println("Original SHA-256: " + originalFileHash);
            System.out.println();

            FileKey fileKey = FileKey.generate();
            List<EncryptedChunk> encryptedChunks = new ArrayList<>();

            for (int i = 0; i < totalChunks; i++) {
                byte[] plaintextChunk = FileChunker.readChunk(tempFile, i, chunkSize);
                EncryptedChunk encrypted = ChunkCipher.encrypt(fileKey, i, plaintextChunk);
                encryptedChunks.add(encrypted);
                System.out.println("Chunk " + i + ": " + plaintextChunk.length + " plaintext bytes -> "
                        + encrypted.ciphertext().length + " ciphertext bytes (nonce: "
                        + encrypted.nonce().length + " bytes)");
            }
            System.out.println();

            ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
            for (EncryptedChunk encrypted : encryptedChunks) {
                byte[] decrypted = ChunkCipher.decrypt(fileKey, encrypted);
                reassembled.write(decrypted);
            }

            byte[] reassembledBytes = reassembled.toByteArray();
            String reassembledText = new String(reassembledBytes, StandardCharsets.UTF_8);
            boolean contentMatches = reassembledText.equals(originalContent);

            Path reassembledFile = Files.createTempFile("p2p-chat-m4a-demo-reassembled", ".txt");
            Files.write(reassembledFile, reassembledBytes);
            String reassembledFileHash = FileChunker.sha256HexOfFile(reassembledFile);
            boolean hashMatches = reassembledFileHash.equals(originalFileHash);

            System.out.println("Reassembled content matches original: " + contentMatches);
            System.out.println("Reassembled SHA-256: " + reassembledFileHash);
            System.out.println("SHA-256 matches (integrity check, spec section 12 step 5): " + hashMatches);
            System.out.println();

            // Prove tampering is actually detected, not just assumed: flip one ciphertext bit
            // and confirm decryption fails loudly rather than silently returning garbage.
            EncryptedChunk original = encryptedChunks.get(0);
            byte[] tamperedCiphertext = original.ciphertext().clone();
            tamperedCiphertext[0] ^= 0x01;
            EncryptedChunk tampered = new EncryptedChunk(original.chunkIndex(), original.nonce(), tamperedCiphertext);
            boolean tamperDetected;
            try {
                ChunkCipher.decrypt(fileKey, tampered);
                tamperDetected = false;
            } catch (RuntimeException e) {
                tamperDetected = true;
                System.out.println("Tamper attempt correctly threw: " + e.getMessage());
            }
            System.out.println("Tampered chunk correctly rejected: " + tamperDetected);

            boolean allOk = contentMatches && hashMatches && tamperDetected;
            System.out.println();
            System.out.println(allOk
                    ? "M4a CONFIRMED: chunking + per-chunk AES-256-GCM encryption/decryption + integrity verification all work correctly."
                    : "M4a FAILED: something did not match \u2014 check the output above.");

            Files.deleteIfExists(reassembledFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
