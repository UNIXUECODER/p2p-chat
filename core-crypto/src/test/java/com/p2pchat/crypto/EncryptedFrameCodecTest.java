package com.p2pchat.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedFrameCodecTest {

    @Test
    void encodeDecodePreKeyFrame() {
        byte[] ciphertext = "prekey_ciphertext_bytes".getBytes();
        EncryptedFrame frame = new EncryptedFrame(true, ciphertext);

        byte[] wire = EncryptedFrameCodec.encode(frame);
        EncryptedFrame decoded = EncryptedFrameCodec.decode(wire);

        assertThat(decoded.isPreKeyMessage()).isTrue();
        assertThat(decoded.ciphertext()).isEqualTo(ciphertext);
    }

    @Test
    void encodeDecodeWhisperFrame() {
        byte[] ciphertext = "whisper_ciphertext_bytes".getBytes();
        EncryptedFrame frame = new EncryptedFrame(false, ciphertext);

        byte[] wire = EncryptedFrameCodec.encode(frame);
        EncryptedFrame decoded = EncryptedFrameCodec.decode(wire);

        assertThat(decoded.isPreKeyMessage()).isFalse();
        assertThat(decoded.ciphertext()).isEqualTo(ciphertext);
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> EncryptedFrameCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Encrypted frame too short");
    }
}
