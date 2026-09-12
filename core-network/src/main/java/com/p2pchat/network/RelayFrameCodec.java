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
 * <p><b>pre-m6h-hardening-plan.md finding B-1:</b> a {@link #PROTOCOL_VERSION} byte now follows
 * the type marker on every frame — done ahead of Track A (relay-as-real-transport) specifically
 * so the new relay frame types that track adds are versioned from birth, per the audit's own
 * sequencing. {@link #decode} rejects an unrecognized version with {@link
 * UnsupportedProtocolVersionException}, not the generic {@link IllegalArgumentException} every
 * other decode failure here throws.
 *
 * <p><b>A-1 update:</b> two more marker values (PING/PONG, see {@link RelayFrameType}) added
 * alongside the original two. Deliberately not a rewrite: {@link #encode}/{@link #decode}'s
 * shape, every exception type and message text, and the version-byte handling are all unchanged,
 * so {@code RelayFrameCodecTest}'s existing cases keep passing against this without modification.
 */
public final class RelayFrameCodec {

    private static final byte FORWARD_MARKER = 0x01;
    private static final byte DELIVER_MARKER = 0x02;
    private static final byte PING_MARKER = 0x03;
    private static final byte PONG_MARKER = 0x04;

    private static final byte PROTOCOL_VERSION = 1;

    private RelayFrameCodec() {
    }

    public static byte[] encode(RelayFrame frame) {
        byte[] peerIdBytes = frame.peerId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 1 + 4 + peerIdBytes.length + frame.payload().length);
        buf.put(markerFor(frame.type()));
        buf.put(PROTOCOL_VERSION);
        buf.putInt(peerIdBytes.length);
        buf.put(peerIdBytes);
        buf.put(frame.payload());
        return buf.array();
    }

    public static RelayFrame decode(byte[] wire) {
        if (wire.length < 2) {
            throw new IllegalArgumentException("Relay frame too short: " + wire.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        RelayFrameType type = typeFor(marker);
        byte version = buf.get();
        if (version != PROTOCOL_VERSION) {
            throw new UnsupportedProtocolVersionException(version, PROTOCOL_VERSION);
        }
        byte[] peerIdBytes = getBytes(buf);
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new RelayFrame(type, new String(peerIdBytes, StandardCharsets.UTF_8), payload);
    }

    private static byte markerFor(RelayFrameType type) {
        return switch (type) {
            case FORWARD -> FORWARD_MARKER;
            case DELIVER -> DELIVER_MARKER;
            case PING -> PING_MARKER;
            case PONG -> PONG_MARKER;
        };
    }

    private static RelayFrameType typeFor(byte marker) {
        if (marker == FORWARD_MARKER) {
            return RelayFrameType.FORWARD;
        } else if (marker == DELIVER_MARKER) {
            return RelayFrameType.DELIVER;
        } else if (marker == PING_MARKER) {
            return RelayFrameType.PING;
        } else if (marker == PONG_MARKER) {
            return RelayFrameType.PONG;
        }
        throw new IllegalArgumentException("Unknown relay frame marker: " + marker);
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
