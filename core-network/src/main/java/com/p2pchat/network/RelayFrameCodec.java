package com.p2pchat.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format: [1 byte marker: 0x01=forward request, 0x02=delivery]
 * [4 bytes peer-id length][peer-id UTF-8 bytes][remaining bytes: payload].
 * Logic verified standalone (encode/decode round-trip, both message kinds)
 * before being wired into RelayProtocol.
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
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        int peerIdLength = buf.getInt();
        byte[] peerIdBytes = new byte[peerIdLength];
        buf.get(peerIdBytes);
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new RelayFrame(marker == FORWARD_MARKER, new String(peerIdBytes, StandardCharsets.UTF_8), payload);
    }
}
