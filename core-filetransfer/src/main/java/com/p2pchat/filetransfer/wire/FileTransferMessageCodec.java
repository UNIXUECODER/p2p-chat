package com.p2pchat.filetransfer.wire;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format, matching core-network's established {@code RelayFrameCodec}/{@code DiscoveryFrameCodec}
 * convention exactly (marker byte + length-prefixed UTF-8 strings + fixed-size fields, "whatever's
 * left is the trailing binary blob"):
 *
 * <pre>
 * FILE_OFFER (marker 6):         [transferId][senderAddress][fileName][8 bytes fileSize]
 *                                 [fileHash][4 bytes chunkSize][4 bytes totalChunks][32 bytes fileKey]
 * FILE_CHUNK_REQUEST (marker 7): [transferId][4 bytes count][count * 4-byte chunk indices]
 * FILE_CHUNK (marker 8):         [transferId][4 bytes chunkIndex][12 bytes nonce]
 *                                 [remaining bytes: ciphertext]
 * </pre>
 *
 * where every {@code [string]} above is {@code [4-byte UTF-8 length][UTF-8 bytes]}, the same
 * convention {@code RelayFrameCodec}/{@code DiscoveryFrameCodec} already use for peer IDs.
 *
 * <p>Marker values reuse docs/architecture-spec.md §6's {@code EnvelopeType} numbering exactly
 * ({@code FILE_OFFER=6}, {@code FILE_CHUNK_REQUEST=7}, {@code FILE_CHUNK=8}).
 *
 * <p>Logic verified standalone — 22 round-trip and edge-case checks (unicode filenames, empty
 * chunk-index arrays, zero-length ciphertext, malformed key/nonce lengths rejected at encode
 * time) — before being wired into any networking. See node-daemon's {@code WireCodecDemoMain}.
 */
public final class FileTransferMessageCodec {

    private static final byte FILE_OFFER_MARKER = 6;
    private static final byte FILE_CHUNK_REQUEST_MARKER = 7;
    private static final byte FILE_CHUNK_MARKER = 8;

    private static final int FILE_KEY_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;

    private FileTransferMessageCodec() {
    }

    public static byte[] encode(FileTransferMessage message) {
        return switch (message) {
            case FileOfferPayload offer -> encodeOffer(offer);
            case FileChunkRequestPayload request -> encodeRequest(request);
            case FileChunkPayload chunk -> encodeChunk(chunk);
        };
    }

    public static FileTransferMessage decode(byte[] wire) {
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        return switch (marker) {
            case FILE_OFFER_MARKER -> decodeOffer(buf);
            case FILE_CHUNK_REQUEST_MARKER -> decodeRequest(buf);
            case FILE_CHUNK_MARKER -> decodeChunk(buf);
            default -> throw new IllegalArgumentException("Unknown file-transfer message marker: " + marker);
        };
    }

    private static byte[] encodeOffer(FileOfferPayload offer) {
        byte[] transferIdBytes = utf8(offer.transferId());
        byte[] senderAddressBytes = utf8(offer.senderAddress());
        byte[] fileNameBytes = utf8(offer.fileName());
        byte[] fileHashBytes = utf8(offer.fileHash());
        if (offer.fileKey().length != FILE_KEY_LENGTH) {
            throw new IllegalArgumentException("fileKey must be " + FILE_KEY_LENGTH + " bytes, got " + offer.fileKey().length);
        }

        int size = 1
                + 4 + transferIdBytes.length
                + 4 + senderAddressBytes.length
                + 4 + fileNameBytes.length
                + 8
                + 4 + fileHashBytes.length
                + 4 + 4
                + FILE_KEY_LENGTH;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(FILE_OFFER_MARKER);
        putString(buf, transferIdBytes);
        putString(buf, senderAddressBytes);
        putString(buf, fileNameBytes);
        buf.putLong(offer.fileSize());
        putString(buf, fileHashBytes);
        buf.putInt(offer.chunkSize());
        buf.putInt(offer.totalChunks());
        buf.put(offer.fileKey());
        return buf.array();
    }

    private static FileOfferPayload decodeOffer(ByteBuffer buf) {
        String transferId = getString(buf);
        String senderAddress = getString(buf);
        String fileName = getString(buf);
        long fileSize = buf.getLong();
        String fileHash = getString(buf);
        int chunkSize = buf.getInt();
        int totalChunks = buf.getInt();
        byte[] fileKey = new byte[FILE_KEY_LENGTH];
        buf.get(fileKey);
        return new FileOfferPayload(transferId, senderAddress, fileName, fileSize, fileHash, chunkSize, totalChunks, fileKey);
    }

    private static byte[] encodeRequest(FileChunkRequestPayload request) {
        byte[] transferIdBytes = utf8(request.transferId());
        int[] indices = request.missingChunkIndices();

        int size = 1 + 4 + transferIdBytes.length + 4 + (indices.length * 4);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(FILE_CHUNK_REQUEST_MARKER);
        putString(buf, transferIdBytes);
        buf.putInt(indices.length);
        for (int index : indices) {
            buf.putInt(index);
        }
        return buf.array();
    }

    private static FileChunkRequestPayload decodeRequest(ByteBuffer buf) {
        String transferId = getString(buf);
        int count = buf.getInt();
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = buf.getInt();
        }
        return new FileChunkRequestPayload(transferId, indices);
    }

    private static byte[] encodeChunk(FileChunkPayload chunk) {
        byte[] transferIdBytes = utf8(chunk.transferId());
        if (chunk.nonce().length != NONCE_LENGTH) {
            throw new IllegalArgumentException("nonce must be " + NONCE_LENGTH + " bytes, got " + chunk.nonce().length);
        }

        int size = 1 + 4 + transferIdBytes.length + 4 + NONCE_LENGTH + chunk.ciphertext().length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(FILE_CHUNK_MARKER);
        putString(buf, transferIdBytes);
        buf.putInt(chunk.chunkIndex());
        buf.put(chunk.nonce());
        buf.put(chunk.ciphertext());
        return buf.array();
    }

    private static FileChunkPayload decodeChunk(ByteBuffer buf) {
        String transferId = getString(buf);
        int chunkIndex = buf.getInt();
        byte[] nonce = new byte[NONCE_LENGTH];
        buf.get(nonce);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);
        return new FileChunkPayload(transferId, chunkIndex, nonce, ciphertext);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putString(ByteBuffer buf, byte[] utf8Bytes) {
        buf.putInt(utf8Bytes.length);
        buf.put(utf8Bytes);
    }

    private static String getString(ByteBuffer buf) {
        int length = buf.getInt();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
