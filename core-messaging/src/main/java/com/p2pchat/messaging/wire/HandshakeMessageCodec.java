package com.p2pchat.messaging.wire;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * pre-m6h-hardening-plan.md finding B-2. Wire shape, matching {@link ChatMessageCodec}'s
 * established convention exactly (marker + version + length-prefixed fields):
 *
 * <pre>
 * HANDSHAKE_INIT (marker 0):     [senderAddress][4 bytes capability count]
 *                                 [repeated: [4-byte length][UTF-8 bytes]]
 * HANDSHAKE_RESPONSE (marker 1): same shape as HANDSHAKE_INIT
 * </pre>
 *
 * where {@code [senderAddress]} is {@code [4-byte length][UTF-8 bytes]}, matching every other
 * string field in this project's wire codecs.
 *
 * Marker values reuse docs/architecture-spec.md §6's {@code EnvelopeType} numbering exactly
 * ({@code HANDSHAKE_INIT=0}, {@code HANDSHAKE_RESPONSE=1}) — the same two values {@code
 * ApplicationMessageRouter} already named and reserved before this codec existed to handle them.
 *
 * <p>Version-byte convention and bounds-checked length-prefix reads follow B-1's pattern exactly
 * — see {@link ChatMessageCodec}'s own Javadoc for the full rationale (marker stays at index 0
 * unmoved for {@code ApplicationMessageRouter}'s peek-based dispatch; unrecognized version throws
 * {@link UnsupportedProtocolVersionException}, not the generic {@link IllegalArgumentException}
 * every other decode failure here throws).
 */
public final class HandshakeMessageCodec {

    private static final byte HANDSHAKE_INIT_MARKER = 0;
    private static final byte HANDSHAKE_RESPONSE_MARKER = 1;

    private static final byte PROTOCOL_VERSION = 1;

    // A generous ceiling against a malicious/corrupt peer forcing a large allocation while
    // decoding untrusted bytes — same discipline as DiscoveryRecordCodec's own MAX_ADDRESSES.
    // Real capability lists today: one entry ("file-transfer"). No plausible honest future growth
    // gets anywhere near this.
    private static final int MAX_CAPABILITIES = 64;
    private static final int MAX_CAPABILITY_LENGTH = 128;
    private static final int MAX_SENDER_ADDRESS_LENGTH = 1024;

    private HandshakeMessageCodec() {
    }

    public static byte[] encode(HandshakeWireMessage message) {
        return switch (message) {
            case HandshakeInitPayload init ->
                    encodeWithMarker(HANDSHAKE_INIT_MARKER, init.senderAddress(), init.supportedCapabilities());
            case HandshakeResponsePayload response -> encodeWithMarker(
                    HANDSHAKE_RESPONSE_MARKER, response.senderAddress(), response.supportedCapabilities());
        };
    }

    public static HandshakeWireMessage decode(byte[] wire) {
        if (wire.length < 2) {
            throw new IllegalArgumentException("Handshake message too short: " + wire.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        if (marker != HANDSHAKE_INIT_MARKER && marker != HANDSHAKE_RESPONSE_MARKER) {
            throw new IllegalArgumentException("Unknown handshake message marker: " + marker);
        }
        byte version = buf.get();
        if (version != PROTOCOL_VERSION) {
            throw new UnsupportedProtocolVersionException(version, PROTOCOL_VERSION);
        }
        String senderAddress = getString(buf, MAX_SENDER_ADDRESS_LENGTH);
        Set<String> capabilities = decodeCapabilities(buf);
        return marker == HANDSHAKE_INIT_MARKER
                ? new HandshakeInitPayload(senderAddress, capabilities)
                : new HandshakeResponsePayload(senderAddress, capabilities);
    }

    private static byte[] encodeWithMarker(byte marker, String senderAddress, Set<String> capabilities) {
        byte[] senderAddressBytes = senderAddress.getBytes(StandardCharsets.UTF_8);
        byte[][] capabilityBytes = capabilities.stream()
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);

        int size = 1 + 1 + 4 + senderAddressBytes.length + 4;
        for (byte[] bytes : capabilityBytes) {
            size += 4 + bytes.length;
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(marker);
        buf.put(PROTOCOL_VERSION);
        buf.putInt(senderAddressBytes.length);
        buf.put(senderAddressBytes);
        buf.putInt(capabilityBytes.length);
        for (byte[] bytes : capabilityBytes) {
            buf.putInt(bytes.length);
            buf.put(bytes);
        }
        return buf.array();
    }

    /** Same bounds-checked length-prefix read as every other codec in this project, plus a
     * caller-supplied ceiling on top of "fits in the buffer" — {@code MAX_ADDRESSES}-style
     * discipline, not just the bare minimum needed to avoid a crash. */
    private static String getString(ByteBuffer buf, int maxLength) {
        int length = buf.getInt();
        if (length < 0 || length > maxLength || length > buf.remaining()) {
            throw new IllegalArgumentException(
                    "Malformed length-prefixed field: length=" + length + ", max=" + maxLength
                            + ", remaining=" + buf.remaining());
        }
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Set<String> decodeCapabilities(ByteBuffer buf) {
        int count = buf.getInt();
        if (count < 0 || count > MAX_CAPABILITIES) {
            throw new IllegalArgumentException(
                    "Malformed capability count: count=" + count + ", max=" + MAX_CAPABILITIES);
        }
        // LinkedHashSet, not HashSet: preserves the order the sender listed capabilities in,
        // which costs nothing and makes a decoded payload's toString()/logging deterministic
        // rather than hash-order-dependent -- a small debuggability win with no real trade-off,
        // since callers only ever query membership (see SessionManager.peerSupports), never rely
        // on iteration order for correctness.
        Set<String> capabilities = new LinkedHashSet<>(count);
        for (int i = 0; i < count; i++) {
            int length = buf.getInt();
            if (length < 0 || length > MAX_CAPABILITY_LENGTH || length > buf.remaining()) {
                throw new IllegalArgumentException(
                        "Malformed capability string length: length=" + length + ", remaining=" + buf.remaining());
            }
            byte[] bytes = new byte[length];
            buf.get(bytes);
            capabilities.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return capabilities;
    }
}
