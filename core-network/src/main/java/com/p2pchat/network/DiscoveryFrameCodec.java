package com.p2pchat.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format: [1 byte marker][4 bytes peer-id length][peer-id UTF-8 bytes]
 * [remaining bytes: payload]. Same shape as RelayFrameCodec, just with 4
 * marker values instead of 2. Logic verified standalone (all 4 message
 * kinds, round-tripped) before being wired into DiscoveryProtocol.
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
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        DiscoveryMessageType type = switch (marker) {
            case PUBLISH_MARKER -> DiscoveryMessageType.PUBLISH;
            case LOOKUP_MARKER -> DiscoveryMessageType.LOOKUP;
            case FOUND_MARKER -> DiscoveryMessageType.LOOKUP_RESPONSE_FOUND;
            case NOT_FOUND_MARKER -> DiscoveryMessageType.LOOKUP_RESPONSE_NOT_FOUND;
            default -> throw new IllegalArgumentException("Unknown discovery frame marker: " + marker);
        };
        int peerIdLength = buf.getInt();
        byte[] peerIdBytes = new byte[peerIdLength];
        buf.get(peerIdBytes);
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new DiscoveryFrame(type, new String(peerIdBytes, StandardCharsets.UTF_8), payload);
    }
}
