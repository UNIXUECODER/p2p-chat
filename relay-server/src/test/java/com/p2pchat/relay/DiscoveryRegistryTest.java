package com.p2pchat.relay;

import com.p2pchat.model.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
