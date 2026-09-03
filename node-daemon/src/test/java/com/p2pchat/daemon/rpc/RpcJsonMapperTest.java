package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonArray;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.identity.Identity;
import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.ConversationType;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RpcJsonMapperTest {

    @Test
    void identityJsonUsesTheCanonicalLibp2pPeerIdNotTheAppIdentityHexId() {
        // docs/M6g-gap-analysis-and-plan.md §1.1's explicit resolution for identity.get: "Must
        // return the libp2p peer ID... not just the app-identity hex ID."
        Identity appIdentity = new Identity("app-identity-hex-id", "Alice", new byte[]{1, 2, 3}, 1000L);
        PeerId canonicalPeerId = PeerId.of("12D3KooWCanonical");

        JsonObject json = RpcJsonMapper.identityJson(appIdentity, canonicalPeerId);

        assertThat(json.get("peerId").asString()).isEqualTo("12D3KooWCanonical");
        assertThat(json.get("displayName").asString()).isEqualTo("Alice");
        assertThat(json.get("identityPublicKey").asString()).isEqualTo(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertThat(json.get("createdAt").asLong()).isEqualTo(1000L);
    }

    @Test
    void contactJsonMapsAllFields() {
        Contact contact = new Contact(PeerId.of("12D3KooWBob"), "Bob", true, 2000L);

        JsonObject json = RpcJsonMapper.contactJson(contact);

        assertThat(json.get("peerId").asString()).isEqualTo("12D3KooWBob");
        assertThat(json.get("displayName").asString()).isEqualTo("Bob");
        assertThat(json.get("verified").asBoolean()).isTrue();
        assertThat(json.get("addedAt").asLong()).isEqualTo(2000L);
    }

    @Test
    void contactsJsonMapsEachEntryInOrder() {
        List<Contact> contacts = List.of(
                new Contact(PeerId.of("12D3KooWA"), "A", false, 1L),
                new Contact(PeerId.of("12D3KooWB"), "B", true, 2L));

        JsonArray json = RpcJsonMapper.contactsJson(contacts);

        assertThat(json.elements()).hasSize(2);
        assertThat(json.get(0).asObject().get("peerId").asString()).isEqualTo("12D3KooWA");
        assertThat(json.get(1).asObject().get("peerId").asString()).isEqualTo("12D3KooWB");
    }

    @Test
    void conversationJsonMapsAllFieldsAndOmitsMembers() {
        Conversation conversation = new Conversation("direct-a-b", ConversationType.DIRECT, "12D3KooWOther", 3000L);

        JsonObject json = RpcJsonMapper.conversationJson(conversation);

        assertThat(json.get("conversationId").asString()).isEqualTo("direct-a-b");
        assertThat(json.get("type").asString()).isEqualTo("DIRECT");
        assertThat(json.get("name").asString()).isEqualTo("12D3KooWOther");
        assertThat(json.get("createdAt").asLong()).isEqualTo(3000L);
        // §4's original sketch mentions "members"; core-storage.model.Conversation has no such
        // field to serialize -- see RpcJsonMapper's own Javadoc for why this is a deliberate
        // omission, not an oversight.
        assertThat(json.has("members")).isFalse();
    }

    @Test
    void messageJsonUsesContentKeyNotPlaintext() {
        // §7's own push-event wire example uses "content", not §4's "plaintext" -- see
        // RpcJsonMapper's own Javadoc for why the wire contract wins.
        Message message = new Message("m-1", "direct-a-b", PeerId.of("12D3KooWSender"), DeviceId.DEFAULT,
                "hlc-1", "text/plain", "hello there".getBytes(StandardCharsets.UTF_8), DeliveryState.DELIVERED, 4000L);

        JsonObject json = RpcJsonMapper.messageJson(message);

        assertThat(json.get("messageId").asString()).isEqualTo("m-1");
        assertThat(json.get("conversationId").asString()).isEqualTo("direct-a-b");
        assertThat(json.get("senderPeerId").asString()).isEqualTo("12D3KooWSender");
        assertThat(json.get("senderDeviceId").asString()).isEqualTo("0");
        assertThat(json.get("hlcTimestamp").asString()).isEqualTo("hlc-1");
        assertThat(json.get("contentType").asString()).isEqualTo("text/plain");
        assertThat(json.get("content").asString()).isEqualTo("hello there");
        assertThat(json.get("state").asString()).isEqualTo("DELIVERED");
        assertThat(json.has("plaintext")).isFalse();
    }

    @Test
    void networkStatusJsonMapsListenAddressesAsAnArrayOfStrings() {
        JsonObject json = RpcJsonMapper.networkStatusJson(PeerId.of("12D3KooWMe"), "Me",
                new String[]{"/ip4/127.0.0.1/tcp/9300/p2p/12D3KooWMe"}, false, 2);

        assertThat(json.get("peerId").asString()).isEqualTo("12D3KooWMe");
        assertThat(json.get("displayName").asString()).isEqualTo("Me");
        assertThat(json.get("listenAddresses").asArray().elements()).hasSize(1);
        assertThat(json.get("listenAddresses").asArray().get(0).asString())
                .isEqualTo("/ip4/127.0.0.1/tcp/9300/p2p/12D3KooWMe");
        assertThat(json.get("relayConnected").asBoolean()).isFalse();
        assertThat(json.get("connectedPeerCount").asLong()).isEqualTo(2L);
    }

    @Test
    void connectedPeerJsonMapsAllFields() {
        JsonObject json = RpcJsonMapper.connectedPeerJson(PeerId.of("12D3KooWPeer"), "Peer Name", 5000L, true);

        assertThat(json.get("peerId").asString()).isEqualTo("12D3KooWPeer");
        assertThat(json.get("displayName").asString()).isEqualTo("Peer Name");
        assertThat(json.get("lastSeen").asLong()).isEqualTo(5000L);
        assertThat(json.get("hasSession").asBoolean()).isTrue();
    }

    @Test
    void connectedPeerJsonToleratesANullDisplayName() {
        // A peer with neither a saved contact nor a cosmetic route name -- see
        // JsonRpcRouter.resolveDisplayName's own fallback chain.
        JsonObject json = RpcJsonMapper.connectedPeerJson(PeerId.of("12D3KooWPeer"), null, 5000L, true);

        assertThat(json.get("displayName").isNull()).isTrue();
    }

    @Test
    void emptyResultIsAnEmptyJsonObject() {
        assertThat(RpcJsonMapper.emptyResult()).isEqualTo(JsonObject.of());
    }
}
