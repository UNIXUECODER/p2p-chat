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
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put((byte) 0x01);
        buf.put((byte) 1); // PROTOCOL_VERSION -- without this, byte 1 of the length below gets
                            // misread as the version byte instead of exercising the length check
                            // this test is actually about (see B-1's own commit message)
        buf.putInt(999_999_999); // far more than what's actually in the buffer
        assertThatThrownBy(() -> RelayFrameCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectNegativePeerIdLength() {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put((byte) 0x01);
        buf.put((byte) 1); // PROTOCOL_VERSION -- see rejectOversizedPeerIdLength's comment
        buf.putInt(-1);
        assertThatThrownBy(() -> RelayFrameCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    // --- pre-m6h-hardening-plan.md finding B-1 ---

    @Test
    void rejectUnsupportedProtocolVersion() {
        byte[] wire = RelayFrameCodec.encode(new RelayFrame(true, "peer-1", "payload".getBytes()));
        wire[1] = 99; // corrupt the version byte only, leave the marker and everything else valid

        assertThatThrownBy(() -> RelayFrameCodec.decode(wire))
                .isInstanceOf(UnsupportedProtocolVersionException.class)
                .isInstanceOf(IllegalArgumentException.class) // still catchable by existing broad catch sites
                .hasMessageContaining("99");
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> RelayFrameCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Relay frame too short");
    }

    // --- A-1 (pre-m6h-hardening-plan.md, Track A): PING/PONG, the keepalive frame types RelaySession uses. ---

    @Test
    void encodeDecodePing() {
        byte[] nonce = ByteBuffer.allocate(8).putLong(42L).array();
        RelayFrame frame = new RelayFrame(RelayFrameType.PING, "", nonce);
        byte[] wire = RelayFrameCodec.encode(frame);

        RelayFrame decoded = RelayFrameCodec.decode(wire);
        assertThat(decoded.type()).isEqualTo(RelayFrameType.PING);
        assertThat(decoded.peerId()).isEmpty();
        assertThat(decoded.payload()).isEqualTo(nonce);
    }

    @Test
    void encodeDecodePong() {
        byte[] nonce = ByteBuffer.allocate(8).putLong(42L).array();
        RelayFrame frame = new RelayFrame(RelayFrameType.PONG, "", nonce);
        byte[] wire = RelayFrameCodec.encode(frame);

        RelayFrame decoded = RelayFrameCodec.decode(wire);
        assertThat(decoded.type()).isEqualTo(RelayFrameType.PONG);
        assertThat(decoded.payload()).isEqualTo(nonce);
    }

    @Test
    void theOldBooleanConstructorStillOnlyProducesForwardOrDeliver() {
        // A-1 added two more kinds, but the M3a constructor/accessor this predates must keep
        // meaning exactly what it always meant -- see RelayFrame's own Javadoc.
        assertThat(new RelayFrame(true, "x", new byte[0]).type()).isEqualTo(RelayFrameType.FORWARD);
        assertThat(new RelayFrame(false, "x", new byte[0]).type()).isEqualTo(RelayFrameType.DELIVER);
    }
}
