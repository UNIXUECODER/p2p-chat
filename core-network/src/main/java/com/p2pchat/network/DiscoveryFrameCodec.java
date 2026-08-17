package com.p2pchat.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format: [1 byte marker][4 bytes peer-id length][peer-id UTF-8 bytes]
 * [remaining bytes: payload]. Same shape as RelayFrameCodec, just with 4
 * marker values instead of 2. Logic verified standalone (all 4 message
 * kinds, round-tripped) before being wired into DiscoveryProtocol.
 *
 * <p><b>Pre-M6 cleanup pass:</b> marker validation here was already correct (the exhaustive
 * {@code switch} with a {@code default -> throw} below predates this pass) — but the peer-id
 * length prefix had the same unchecked-allocation gap {@code RelayFrameCodec} did; see that
 * class's Javadoc for why. Fixed the same way here.
 */
public final class DiscoveryFrameCodec {

    private static final byte PUBLISH_MARKER = 0x01;
    private static final byte LOOKUP_MARKER = 0x02;
    private static final byte FOUND_MARKER = 0x03;
    private static final byte NOT_FOUND_MARKER = 0x04;

    private DiscoveryFrameCodec() {
    }

    public static byte[] encode(DiscoveryFrame frame) {
        byte marker = switch (frame.type()) {
            case PUBLISH -> PUBLISH_MARKER;
            case LOOKUP -> LOOKUP_MARKER;
            case LOOKUP_RESPONSE_FOUND -> FOUND_MARKER;
            case LOOKUP_RESPONSE_NOT_FOUND -> NOT_FOUND_MARKER;
        };
        byte[] peerIdBytes = frame.peerId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + peerIdBytes.length + frame.payload().length);
        buf.put(marker);
        buf.putInt(peerIdBytes.length);
        buf.put(peerIdBytes);
        buf.put(frame.payload());
        return buf.array();
    }

    public static DiscoveryFrame decode(byte[] wire) {
        if (wire.length < 1) {
            throw new IllegalArgumentException("Discovery frame too short: " + wire.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        DiscoveryMessageType type = switch (marker) {
            case PUBLISH_MARKER -> DiscoveryMessageType.PUBLISH;
            case LOOKUP_MARKER -> DiscoveryMessageType.LOOKUP;
            case FOUND_MARKER -> DiscoveryMessageType.LOOKUP_RESPONSE_FOUND;
            case NOT_FOUND_MARKER -> DiscoveryMessageType.LOOKUP_RESPONSE_NOT_FOUND;
            default -> throw new IllegalArgumentException("Unknown discovery frame marker: " + marker);
        };
        byte[] peerIdBytes = getBytes(buf);
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new DiscoveryFrame(type, new String(peerIdBytes, StandardCharsets.UTF_8), payload);
    }

    /** Same bounds-checked read as {@code RelayFrameCodec}'s own {@code getBytes} — see its Javadoc. */
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
