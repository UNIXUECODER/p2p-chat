package com.p2pchat.filetransfer;

import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.filetransfer.wire.FileTransferMessageCodec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTransferMessageCodecTest {

    @Test
    void offerRoundTrip() {
        FileKey key = FileKey.generate();
        FileOfferPayload offer = new FileOfferPayload(
                "transfer-123",
                "/ip4/127.0.0.1/tcp/9000/p2p/12D3KooW...",
                "test-document.pdf",
                1024L * 1024L,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                256 * 1024,
                4,
                key.bytes()
        );

        byte[] wire = FileTransferMessageCodec.encode(offer);
        FileTransferMessage decoded = FileTransferMessageCodec.decode(wire);

        assertThat(decoded).isInstanceOf(FileOfferPayload.class);
        FileOfferPayload decodedOffer = (FileOfferPayload) decoded;
        assertThat(decodedOffer.transferId()).isEqualTo(offer.transferId());
        assertThat(decodedOffer.senderAddress()).isEqualTo(offer.senderAddress());
        assertThat(decodedOffer.fileName()).isEqualTo(offer.fileName());
        assertThat(decodedOffer.fileSize()).isEqualTo(offer.fileSize());
        assertThat(decodedOffer.fileHash()).isEqualTo(offer.fileHash());
        assertThat(decodedOffer.fileKey()).isEqualTo(offer.fileKey());
    }

    @Test
    void chunkRequestRoundTrip() {
        FileChunkRequestPayload request = new FileChunkRequestPayload(
                "transfer-123",
                new int[]{0, 2, 3}
        );

        byte[] wire = FileTransferMessageCodec.encode(request);
        FileTransferMessage decoded = FileTransferMessageCodec.decode(wire);

        assertThat(decoded).isInstanceOf(FileChunkRequestPayload.class);
        FileChunkRequestPayload decodedReq = (FileChunkRequestPayload) decoded;
        assertThat(decodedReq.transferId()).isEqualTo("transfer-123");
        assertThat(decodedReq.missingChunkIndices()).containsExactly(0, 2, 3);
    }

    @Test
    void chunkPayloadRoundTrip() {
        byte[] nonce = new byte[12];
        byte[] ciphertext = "encrypted_bytes_here".getBytes();
        FileChunkPayload chunk = new FileChunkPayload(
                "transfer-123",
                1,
                nonce,
                ciphertext
        );

        byte[] wire = FileTransferMessageCodec.encode(chunk);
        FileTransferMessage decoded = FileTransferMessageCodec.decode(wire);

        assertThat(decoded).isInstanceOf(FileChunkPayload.class);
        FileChunkPayload decodedChunk = (FileChunkPayload) decoded;
        assertThat(decodedChunk.transferId()).isEqualTo("transfer-123");
        assertThat(decodedChunk.chunkIndex()).isEqualTo(1);
        assertThat(decodedChunk.nonce()).isEqualTo(nonce);
        assertThat(decodedChunk.ciphertext()).isEqualTo(ciphertext);
    }

    @Test
    void rejectInvalidMarker() {
        byte[] badWire = new byte[]{0x00, 0x01, 0x02};
        assertThatThrownBy(() -> FileTransferMessageCodec.decode(badWire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown file-transfer message marker");
    }

    // --- Pre-M6 cleanup pass: length-prefixed string/array fields had no bounds check at all —
    // same gap RelayFrameCodec had (see its own test/Javadoc), fixed the same way here. ---

    @Test
    void rejectOversizedStringLength() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 6); // FILE_OFFER_MARKER
        buf.putInt(Integer.MAX_VALUE); // transferId's claimed length
        assertThatThrownBy(() -> FileTransferMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectOversizedChunkIndexCount() {
        ByteBuffer buf = ByteBuffer.allocate(9);
        buf.put((byte) 7); // FILE_CHUNK_REQUEST_MARKER
        buf.putInt(0); // empty (valid) transferId string
        buf.putInt(Integer.MAX_VALUE); // absurd chunk-index count
        assertThatThrownBy(() -> FileTransferMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed chunk-index count");
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> FileTransferMessageCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File-transfer message too short");
    }
}
