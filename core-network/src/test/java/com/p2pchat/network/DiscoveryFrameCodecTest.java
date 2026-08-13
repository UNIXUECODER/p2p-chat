package com.p2pchat.network;

import org.junit.jupiter.api.Test;

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
}
