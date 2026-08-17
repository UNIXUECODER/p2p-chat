package com.p2pchat.crypto;

import java.util.Arrays;

/**
 * Frames an EncryptedFrame as bytes for the wire (to send over core-network's
 * Envelope protocol), and parses it back. Format: [1 byte: 0x01 = PreKey
 * message, 0x02 = Whisper message][remaining bytes: serialized ciphertext].
 *
 * Deliberately our own explicit marker rather than trying to infer the type
 * from libsignal's internal wire format — keeps this fully within our own
 * control and easy to verify (see the standalone round-trip test that
 * confirmed this exact logic before it was wired into anything else).
 *
 * <p><b>Pre-M6 cleanup pass:</b> {@link #decode} previously treated any marker byte other than
 * {@code 0x01} as {@code 0x02} (Whisper) — {@code wire[0] == PREKEY_MARKER} with no else branch,
 * so a corrupted or malformed marker byte silently became a wrong-but-plausible decode instead
 * of a loud failure. Now rejects anything that isn't exactly one of the two defined markers.
 */
public final class EncryptedFrameCodec {

    private static final byte PREKEY_MARKER = 0x01;
    private static final byte WHISPER_MARKER = 0x02;

    private EncryptedFrameCodec() {
    }

    public static byte[] encode(EncryptedFrame frame) {
        byte[] out = new byte[1 + frame.ciphertext().length];
        out[0] = frame.isPreKeyMessage() ? PREKEY_MARKER : WHISPER_MARKER;
        System.arraycopy(frame.ciphertext(), 0, out, 1, frame.ciphertext().length);
        return out;
    }

    public static EncryptedFrame decode(byte[] wire) {
        if (wire.length < 1) {
            throw new IllegalArgumentException("Encrypted frame too short: " + wire.length + " bytes");
        }
        boolean isPreKeyMessage;
        if (wire[0] == PREKEY_MARKER) {
            isPreKeyMessage = true;
        } else if (wire[0] == WHISPER_MARKER) {
            isPreKeyMessage = false;
        } else {
            throw new IllegalArgumentException("Unknown encrypted frame marker: " + wire[0]);
        }
        byte[] ciphertext = Arrays.copyOfRange(wire, 1, wire.length);
        return new EncryptedFrame(isPreKeyMessage, ciphertext);
    }
}
