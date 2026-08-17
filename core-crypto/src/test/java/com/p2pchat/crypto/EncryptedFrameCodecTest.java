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

    // --- Pre-M6 cleanup pass: decode() used to treat any marker byte other than 0x01 as 0x02
    // (Whisper) — no else branch at all — so a corrupted or malformed marker silently produced a
    // wrong-but-plausible decode instead of a loud failure. ---

    @Test
    void rejectUnknownMarker() {
        byte[] wire = {(byte) 0x99, 1, 2, 3};
        assertThatThrownBy(() -> EncryptedFrameCodec.decode(wire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown encrypted frame marker");
    }
}
