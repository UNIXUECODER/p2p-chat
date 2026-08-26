package com.p2pchat.relay;

import com.p2pchat.discovery.DiscoveryRecord;
import com.p2pchat.discovery.DiscoveryRecordCodec;
import com.p2pchat.discovery.Ed25519RecordKeys;
import com.p2pchat.model.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryRegistryTest {

    private DiscoveryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DiscoveryRegistry();
    }

    @Test
    void publishAndLookupRecord() {
        PeerId publisher = PeerId.of("12D3KooWPublisherPeer12345678901234567890");
        byte[] payload = "sample-prekey-bundle-bytes".getBytes();

        registry.onPublish(publisher, payload);

        byte[] found = registry.onLookup(publisher.toString());
        assertThat(found).isNotNull().isEqualTo(payload);
    }

    @Test
    void lookupNonExistentRecordReturnsNull() {
        byte[] found = registry.onLookup("12D3KooWUnknownPeer");
        assertThat(found).isNull();
    }

    @Test
    void overwriteExistingRecord() {
        PeerId publisher = PeerId.of("12D3KooWPublisherPeer12345678901234567890");
        byte[] payload1 = "bundle-v1".getBytes();
        byte[] payload2 = "bundle-v2".getBytes();

        registry.onPublish(publisher, payload1);
        registry.onPublish(publisher, payload2);

        byte[] found = registry.onLookup(publisher.toString());
        assertThat(found).isEqualTo(payload2);
    }

    // --- M6f: expiry peek on well-formed DiscoveryRecordV2 payloads ---

    @Test
    void withholdsAnExpiredV2Record() throws Exception {
        SignedIdentity id = generateSignedIdentity();
        DiscoveryRecord expired = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId), null, null,
                System.currentTimeMillis() - 1_000);
        byte[] wire = DiscoveryRecordCodec.encodeSigned(expired, id.rawPublicKey, id.rawPrivateKeySeed);

        registry.onPublish(PeerId.of(id.peerId), wire);

        assertThat(registry.onLookup(id.peerId)).isNull();
    }

    @Test
    void servesAnUnexpiredV2Record() throws Exception {
        SignedIdentity id = generateSignedIdentity();
        DiscoveryRecord fresh = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + id.peerId), null, null,
                System.currentTimeMillis() + 60_000);
        byte[] wire = DiscoveryRecordCodec.encodeSigned(fresh, id.rawPublicKey, id.rawPrivateKeySeed);

        registry.onPublish(PeerId.of(id.peerId), wire);

        assertThat(registry.onLookup(id.peerId)).isEqualTo(wire);
    }

    @Test
    void nonV2PayloadsAreServedOpaquelyRegardlessOfExpiryLogic() {
        // A payload that isn't a DiscoveryRecordV2 at all (e.g. the plain opaque bytes
        // PublishRecordMain still publishes) must never be mistaken for expired.
        PeerId publisher = PeerId.of("12D3KooWLegacyPublisher123456789012345678");
        byte[] arbitraryBytes = "not-a-v2-record-at-all".getBytes();

        registry.onPublish(publisher, arbitraryBytes);

        assertThat(registry.onLookup(publisher.toString())).isEqualTo(arbitraryBytes);
    }

    private record SignedIdentity(byte[] rawPublicKey, byte[] rawPrivateKeySeed, String peerId) {
    }

    private static SignedIdentity generateSignedIdentity() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] rawPublicKey = Ed25519RecordKeys.rawPublicKeyFromX509(kp.getPublic().getEncoded());
        byte[] pkcs8 = kp.getPrivate().getEncoded();
        byte[] rawPrivateKeySeed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
        String peerId = Ed25519RecordKeys.peerIdFromRawPublicKey(rawPublicKey);
        return new SignedIdentity(rawPublicKey, rawPrivateKeySeed, peerId);
    }
}
