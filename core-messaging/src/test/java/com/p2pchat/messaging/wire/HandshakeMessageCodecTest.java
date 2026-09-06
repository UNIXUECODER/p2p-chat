package com.p2pchat.messaging.wire;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * pre-m6h-hardening-plan.md finding B-2. Same structure as {@code ChatMessageCodecTest}/{@code
 * FileTransferMessageCodecTest}: round-trip tests proving the codec agrees with itself, plus
 * malformed-input tests proving it rejects what it should — not exhaustive adversarial coverage
 * (that's C-5's job, tracked separately, not yet started).
 */
class HandshakeMessageCodecTest {

    private static final String SENDER_ADDRESS = "/ip4/127.0.0.1/tcp/9100/p2p/12D3KooWSender";

    @Test
    void initRoundTrip() {
        HandshakeInitPayload original = new HandshakeInitPayload(SENDER_ADDRESS, Set.of("file-transfer"));

        HandshakeWireMessage decoded = HandshakeMessageCodec.decode(HandshakeMessageCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void responseRoundTrip() {
        HandshakeResponsePayload original = new HandshakeResponsePayload(SENDER_ADDRESS, Set.of("file-transfer"));

        HandshakeWireMessage decoded = HandshakeMessageCodec.decode(HandshakeMessageCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void multipleCapabilitiesRoundTripRegardlessOfOrder() {
        // Set.equals() is order-independent by contract, and decode() uses a LinkedHashSet while
        // the original here is Set.of(...) -- different implementations, same elements, still
        // expected equal. Proves that's actually true here, not just assumed from the JDK docs.
        HandshakeInitPayload original = new HandshakeInitPayload(
                SENDER_ADDRESS, Set.of("file-transfer", "relay-spool", "groups"));

        HandshakeWireMessage decoded = HandshakeMessageCodec.decode(HandshakeMessageCodec.encode(original));

        assertThat(((HandshakeInitPayload) decoded).supportedCapabilities())
                .containsExactlyInAnyOrder("file-transfer", "relay-spool", "groups");
    }

    @Test
    void emptyCapabilitySetRoundTrips() {
        // A peer that supports nothing beyond baseline chat is a legitimate, expected case -- not
        // a malformed one. Nothing in this codec should require at least one capability.
        HandshakeInitPayload original = new HandshakeInitPayload(SENDER_ADDRESS, Set.of());

        HandshakeWireMessage decoded = HandshakeMessageCodec.decode(HandshakeMessageCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void rejectUnknownMarker() {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put((byte) 2); // valid version, but marker 2 belongs to CHAT_MESSAGE, not this codec
        buf.put((byte) 1);
        buf.putInt(0);
        assertThatThrownBy(() -> HandshakeMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown handshake message marker");
    }

    @Test
    void rejectOversizedCapabilityCount() {
        ByteBuffer buf = ByteBuffer.allocate(14);
        buf.put((byte) 0); // HANDSHAKE_INIT_MARKER
        buf.put((byte) 1); // PROTOCOL_VERSION
        buf.putInt(0); // empty (valid) senderAddress
        buf.putInt(Integer.MAX_VALUE); // absurd capability count
        assertThatThrownBy(() -> HandshakeMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed capability count");
    }

    @Test
    void rejectOversizedCapabilityStringLength() {
        ByteBuffer buf = ByteBuffer.allocate(18);
        buf.put((byte) 0); // HANDSHAKE_INIT_MARKER
        buf.put((byte) 1); // PROTOCOL_VERSION
        buf.putInt(0); // empty (valid) senderAddress
        buf.putInt(1); // one capability follows
        buf.putInt(Integer.MAX_VALUE); // that capability's claimed length
        assertThatThrownBy(() -> HandshakeMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed capability string length");
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> HandshakeMessageCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Handshake message too short");
    }

    // --- pre-m6h-hardening-plan.md finding B-1 (this codec was born versioned, but still needs
    // the same coverage every other codec's version check gets) ---

    @Test
    void rejectUnsupportedProtocolVersion() {
        byte[] wire = HandshakeMessageCodec.encode(new HandshakeInitPayload(SENDER_ADDRESS, Set.of()));
        wire[1] = 99; // corrupt the version byte only, leave the marker and everything else valid

        assertThatThrownBy(() -> HandshakeMessageCodec.decode(wire))
                .isInstanceOf(UnsupportedProtocolVersionException.class)
                .isInstanceOf(IllegalArgumentException.class) // still catchable by existing broad catch sites
                .hasMessageContaining("99");
    }
}
