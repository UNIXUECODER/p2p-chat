package com.p2pchat.network;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
