package com.p2pchat.discovery;

import com.p2pchat.model.PeerId;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryRecordCodecTest {

    private record Identity(byte[] rawPublicKey, byte[] rawPrivateKeySeed, String peerId) {
    }

    private static Identity generateIdentity() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] rawPublicKey = Ed25519RecordKeys.rawPublicKeyFromX509(kp.getPublic().getEncoded());
        byte[] pkcs8 = kp.getPrivate().getEncoded();
        byte[] rawSeed = java.util.Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
        String peerId = Ed25519RecordKeys.peerIdFromRawPublicKey(rawPublicKey);
        return new Identity(rawPublicKey, rawSeed, peerId);
    }

    @Test
    void roundTripsAFullyPopulatedRecord() throws Exception {
        Identity id = generateIdentity();
        long expiresAt = System.currentTimeMillis() + 60_000;
        DiscoveryRecord original = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId(), "/ip4/198.51.100.9/tcp/9000/p2p/" + id.peerId()),
                "fake-encoded-prekey-bundle-bytes".getBytes(),
                "/ip4/198.51.100.1/tcp/4001/p2p/12D3KooWRelayPlaceholder",
                expiresAt
        );

        byte[] wire = DiscoveryRecordCodec.encodeSigned(original, id.rawPublicKey(), id.rawPrivateKeySeed());
        DiscoveryRecord decoded = DiscoveryRecordCodec.verifyAndDecode(
                wire, new PeerId(id.peerId()), System.currentTimeMillis());

        assertThat(decoded.addresses()).isEqualTo(original.addresses());
        assertThat(decoded.preKeyBundle()).isEqualTo(original.preKeyBundle());
        assertThat(decoded.relayMultiaddr()).isEqualTo(original.relayMultiaddr());
        assertThat(decoded.expiresAt()).isEqualTo(original.expiresAt());
    }

    @Test
    void roundTripsAMinimalRecordWithNoBundleOrRelay() throws Exception {
        Identity id = generateIdentity();
        DiscoveryRecord original = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId()),
                null,
                null,
                System.currentTimeMillis() + 60_000
        );

        byte[] wire = DiscoveryRecordCodec.encodeSigned(original, id.rawPublicKey(), id.rawPrivateKeySeed());
        DiscoveryRecord decoded = DiscoveryRecordCodec.verifyAndDecode(
                wire, new PeerId(id.peerId()), System.currentTimeMillis());

        assertThat(decoded.hasPreKeyBundle()).isFalse();
        assertThat(decoded.hasRelayMultiaddr()).isFalse();
        assertThat(decoded.preKeyBundle()).isNull();
        assertThat(decoded.relayMultiaddr()).isNull();
    }

    @Test
    void rejectsARecordTamperedAfterSigning() throws Exception {
        Identity id = generateIdentity();
        DiscoveryRecord original = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId()),
                "real-bundle".getBytes(),
                null,
                System.currentTimeMillis() + 60_000
        );
        byte[] wire = DiscoveryRecordCodec.encodeSigned(original, id.rawPublicKey(), id.rawPrivateKeySeed());

        // Flip one byte well inside the signed region (the address bytes), simulating a relay
        // — malicious or merely corrupt — substituting different content into a stored record.
        byte[] tampered = wire.clone();
        int flipAt = tampered.length / 3;
        tampered[flipAt] ^= 0x01;

        assertThatThrownBy(() -> DiscoveryRecordCodec.verifyAndDecode(
                tampered, new PeerId(id.peerId()), System.currentTimeMillis()))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isIn(
                                DiscoveryRecordException.Reason.BAD_SIGNATURE,
                                DiscoveryRecordException.Reason.MALFORMED));
    }

    @Test
    void rejectsARecordSignedByADifferentPeerThanTheOneLookedUp() throws Exception {
        Identity publisher = generateIdentity();
        Identity attacker = generateIdentity();
        DiscoveryRecord maliciousBundle = new DiscoveryRecord(
                List.of("/ip4/198.51.100.66/tcp/9000/p2p/" + attacker.peerId()),
                "attackers-own-bundle".getBytes(),
                null,
                System.currentTimeMillis() + 60_000
        );

        // Attacker signs a perfectly valid, self-consistent record — just not for the peer ID
        // being looked up. This is exactly the substitution a compromised relay could attempt.
        byte[] wire = DiscoveryRecordCodec.encodeSigned(
                maliciousBundle, attacker.rawPublicKey(), attacker.rawPrivateKeySeed());

        assertThatThrownBy(() -> DiscoveryRecordCodec.verifyAndDecode(
                wire, new PeerId(publisher.peerId()), System.currentTimeMillis()))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isEqualTo(DiscoveryRecordException.Reason.PEER_ID_MISMATCH));
    }

    @Test
    void rejectsAnExpiredRecord() throws Exception {
        Identity id = generateIdentity();
        DiscoveryRecord expired = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId()),
                null, null,
                System.currentTimeMillis() - 1_000 // already in the past
        );
        byte[] wire = DiscoveryRecordCodec.encodeSigned(expired, id.rawPublicKey(), id.rawPrivateKeySeed());

        assertThatThrownBy(() -> DiscoveryRecordCodec.verifyAndDecode(
                wire, new PeerId(id.peerId()), System.currentTimeMillis()))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isEqualTo(DiscoveryRecordException.Reason.EXPIRED));
    }

    @Test
    void rejectsTruncatedBytes() throws Exception {
        Identity id = generateIdentity();
        DiscoveryRecord original = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId()), null, null,
                System.currentTimeMillis() + 60_000);
        byte[] wire = DiscoveryRecordCodec.encodeSigned(original, id.rawPublicKey(), id.rawPrivateKeySeed());
        byte[] truncated = java.util.Arrays.copyOf(wire, wire.length / 2);

        assertThatThrownBy(() -> DiscoveryRecordCodec.decodeUnverified(truncated))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isEqualTo(DiscoveryRecordException.Reason.MALFORMED));
    }

    @Test
    void rejectsGarbageBytes() {
        byte[] garbage = new byte[]{0x7f, 0x00, 0x01, 0x02, 0x03};
        assertThatThrownBy(() -> DiscoveryRecordCodec.decodeUnverified(garbage))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isEqualTo(DiscoveryRecordException.Reason.MALFORMED));
    }

    @Test
    void rejectsEmptyBytes() {
        assertThatThrownBy(() -> DiscoveryRecordCodec.decodeUnverified(new byte[0]))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isEqualTo(DiscoveryRecordException.Reason.MALFORMED));
    }

    @Test
    void rejectsAnImplausiblyLargeAddressCountWithoutAllocating() {
        // Marker byte + an address count claiming far more than MAX_ADDRESSES, then nothing
        // else — must fail fast on the count check, not attempt to read MAX_INT addresses.
        byte[] wire = new byte[]{0x02, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertThatThrownBy(() -> DiscoveryRecordCodec.decodeUnverified(wire))
                .isInstanceOfSatisfying(DiscoveryRecordException.class,
                        e -> assertThat(e.reason()).isEqualTo(DiscoveryRecordException.Reason.MALFORMED));
    }

    @Test
    void decodeUnverifiedDoesNotThrowOnAStructurallyValidButBadlySignedRecord() throws Exception {
        // DiscoveryRegistry's expiry peek relies on being able to decode a record's structure
        // — including its expiry field — without needing (or performing) signature
        // verification. Confirm decodeUnverified genuinely doesn't verify anything.
        Identity id = generateIdentity();
        DiscoveryRecord original = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId()), null, null,
                System.currentTimeMillis() + 60_000);
        byte[] wire = DiscoveryRecordCodec.encodeSigned(original, id.rawPublicKey(), id.rawPrivateKeySeed());
        byte[] tampered = wire.clone();
        tampered[wire.length / 3] ^= 0x01;

        SignedDiscoveryRecord decoded = DiscoveryRecordCodec.decodeUnverified(tampered);

        assertThat(decoded.record().expiresAt()).isEqualTo(original.expiresAt());
        // The signature is now invalid, but decodeUnverified itself never checked that.
        assertThat(decoded.verifySignature()).isFalse();
    }
}
