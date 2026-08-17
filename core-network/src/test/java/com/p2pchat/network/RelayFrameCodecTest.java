package com.p2pchat.network;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayFrameCodecTest {

    @Test
    void encodeDecodeForwardRequest() {
        RelayFrame frame = new RelayFrame(true, "peer-123", "hello payload".getBytes());
        byte[] wire = RelayFrameCodec.encode(frame);

        RelayFrame decoded = RelayFrameCodec.decode(wire);
        assertThat(decoded.isForwardRequest()).isTrue();
        assertThat(decoded.peerId()).isEqualTo("peer-123");
        assertThat(decoded.payload()).isEqualTo("hello payload".getBytes());
    }

    @Test
    void encodeDecodeDelivery() {
        RelayFrame frame = new RelayFrame(false, "sender-456", "delivery data".getBytes());
        byte[] wire = RelayFrameCodec.encode(frame);

        RelayFrame decoded = RelayFrameCodec.decode(wire);
        assertThat(decoded.isForwardRequest()).isFalse();
        assertThat(decoded.peerId()).isEqualTo("sender-456");
        assertThat(decoded.payload()).isEqualTo("delivery data".getBytes());
    }

    // --- Pre-M6 cleanup pass: decode() used to accept any marker byte other than 0x01 as
    // delivery (0x02), and read the peer-id length prefix with no bounds check at all. ---

    @Test
    void rejectUnknownMarker() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0x99);
        buf.putInt(0);
        assertThatThrownBy(() -> RelayFrameCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown relay frame marker");
    }

    @Test
    void rejectOversizedPeerIdLength() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0x01);
        buf.putInt(999_999_999); // far more than what's actually in the buffer
        assertThatThrownBy(() -> RelayFrameCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectNegativePeerIdLength() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0x01);
        buf.putInt(-1);
        assertThatThrownBy(() -> RelayFrameCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> RelayFrameCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Relay frame too short");
    }
}
