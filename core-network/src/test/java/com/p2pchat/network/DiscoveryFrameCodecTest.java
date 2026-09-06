package com.p2pchat.network;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryFrameCodecTest {

    @Test
    void encodeDecodeAllDiscoveryTypes() {
        for (DiscoveryMessageType type : DiscoveryMessageType.values()) {
            DiscoveryFrame frame = new DiscoveryFrame(type, "peer-target-789", ("payload-for-" + type).getBytes());
            byte[] wire = DiscoveryFrameCodec.encode(frame);

            DiscoveryFrame decoded = DiscoveryFrameCodec.decode(wire);
            assertThat(decoded.type()).isEqualTo(type);
            assertThat(decoded.peerId()).isEqualTo("peer-target-789");
            assertThat(decoded.payload()).isEqualTo(("payload-for-" + type).getBytes());
        }
    }

    @Test
    void rejectUnknownMarker() {
        byte[] badWire = new byte[]{0x7F, 0x00, 0x00, 0x00, 0x01, 'a'};
        assertThatThrownBy(() -> DiscoveryFrameCodec.decode(badWire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown discovery frame marker");
    }

    // --- Pre-M6 cleanup pass: the peer-id length prefix had no bounds check at all — same gap
    // RelayFrameCodec had (see its own test/Javadoc), fixed the same way here. ---

    @Test
    void rejectOversizedPeerIdLength() {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put((byte) 0x01); // PUBLISH_MARKER
        buf.put((byte) 1); // PROTOCOL_VERSION -- without this, byte 1 of Integer.MAX_VALUE below
                            // gets misread as the version byte instead of exercising the length
                            // check this test is actually about (see B-1's own commit message)
        buf.putInt(Integer.MAX_VALUE);
        assertThatThrownBy(() -> DiscoveryFrameCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> DiscoveryFrameCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discovery frame too short");
    }

    // --- pre-m6h-hardening-plan.md finding B-1 ---

    @Test
    void rejectUnsupportedProtocolVersion() {
        byte[] wire = DiscoveryFrameCodec.encode(
                new DiscoveryFrame(DiscoveryMessageType.LOOKUP, "peer-target-789", "payload".getBytes()));
        wire[1] = 99; // corrupt the version byte only, leave the marker and everything else valid

        assertThatThrownBy(() -> DiscoveryFrameCodec.decode(wire))
                .isInstanceOf(UnsupportedProtocolVersionException.class)
                .isInstanceOf(IllegalArgumentException.class) // still catchable by existing broad catch sites
                .hasMessageContaining("99");
    }
}
