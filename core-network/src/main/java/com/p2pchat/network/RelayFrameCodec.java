package com.p2pchat.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format: [1 byte marker: 0x01=forward request, 0x02=delivery]
 * [4 bytes peer-id length][peer-id UTF-8 bytes][remaining bytes: payload].
 * Logic verified standalone (encode/decode round-trip, both message kinds)
 * before being wired into RelayProtocol.
 *
 * <p><b>Pre-M6 cleanup pass:</b> {@link #decode} previously accepted any marker byte other than
 * {@code 0x01} as {@code 0x02} (delivery) — same latent bug {@code EncryptedFrameCodec} had, and
 * security-relevant here specifically because {@code relay-server} runs this decode against
 * bytes from arbitrary connecting peers, not just this project's own two ends of a session. Also
 * previously read the peer-id length prefix with no bounds check at all — {@code new
 * byte[peerIdLength]} on an adversarial or corrupted length could throw
 * {@code NegativeArraySizeException} on a negative value or attempt an unbounded allocation on a
 * huge one. Both fixed: unknown markers are rejected, and any length-prefixed field's length is
 * checked against the buffer's actual remaining bytes before allocating.
 */
public final class RelayFrameCodec {

    private static final byte FORWARD_MARKER = 0x01;
    private static final byte DELIVER_MARKER = 0x02;

    private RelayFrameCodec() {
    }

    public static byte[] encode(RelayFrame frame) {
        byte[] peerIdBytes = frame.peerId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + peerIdBytes.length + frame.payload().length);
        buf.put(frame.isForwardRequest() ? FORWARD_MARKER : DELIVER_MARKER);
        buf.putInt(peerIdBytes.length);
        buf.put(peerIdBytes);
        buf.put(frame.payload());
        return buf.array();
    }

    public static RelayFrame decode(byte[] wire) {
        if (wire.length < 1) {
            throw new IllegalArgumentException("Relay frame too short: " + wire.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        boolean isForwardRequest;
        if (marker == FORWARD_MARKER) {
            isForwardRequest = true;
        } else if (marker == DELIVER_MARKER) {
            isForwardRequest = false;
        } else {
            throw new IllegalArgumentException("Unknown relay frame marker: " + marker);
        }
        byte[] peerIdBytes = getBytes(buf);
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new RelayFrame(isForwardRequest, new String(peerIdBytes, StandardCharsets.UTF_8), payload);
    }

    /**
     * Reads a {@code [4-byte length][bytes]} field, rejecting a length that is negative or
     * exceeds what the buffer actually has left — either signals malformed/truncated/adversarial
     * input, and both would otherwise reach {@code new byte[length]} directly (see this class's
     * own Javadoc for why that's a real hazard, not just hygiene).
     */
    private static byte[] getBytes(ByteBuffer buf) {
        int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException(
                    "Malformed length-prefixed field: length=" + length + ", remaining=" + buf.remaining());
        }
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return bytes;
    }
}
