package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonArray;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonString;
import com.p2pchat.daemon.json.JsonValue;
import com.p2pchat.identity.Identity;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.PeerRoute;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * M6g-4: maps this project's domain records onto the JSON shapes {@code
 * docs/architecture-spec.md §4}/{@code §7} describe, kept separate from {@link JsonRpcRouter}'s
 * own dispatch logic — the same separation-of-concerns split this project already draws between
 * orchestration classes ({@code ContactService}) and codecs ({@code DiscoveryRecordCodec}), even
 * though this class's own job (Java record → {@code JsonValue} tree) is narrower than a full
 * codec's (no parsing direction — nothing in this milestone's scope ever needs to turn a client-
 * supplied JSON object back into one of these domain records; every RPC method that accepts one
 * of these shapes as input reads individual fields directly via {@link JsonRpcRequest}'s own
 * accessors instead).
 *
 * <p><b>Field names follow {@code §4}'s domain-model sketch, except where {@code §7}'s own wire
 * examples explicitly diverge</b> — namely {@link #messageJson}, where {@code §7}'s push-event
 * example uses {@code content} for a message's decrypted body, not {@code §4}'s {@code
 * plaintext}. Since {@code §7} is the actual wire contract a frontend builds against (per {@code
 * docs/M6g-gap-analysis-and-plan.md §2.2}: "§7 is the spec... where they disagree, the spec
 * wins"), this follows {@code content} — the Java field is still {@code Message.plaintext()},
 * only the JSON key differs, and only here, not internally.
 */
final class RpcJsonMapper {

    private RpcJsonMapper() {
    }

    /**
     * @param canonicalPeerId the libp2p peer id ({@code SessionManager.localPeerId()}) — used
     *                        for the {@code peerId} field in place of {@code appIdentity.peerId()}
     *                        (the app-identity's own internal hex id), per {@code
     *                        docs/M6g-gap-analysis-and-plan.md §1.1}'s explicit resolution for
     *                        {@code identity.get}: "Must return the libp2p peer ID (canonical
     *                        runtime identity), not just the app-identity hex ID." Applied to
     *                        {@code identity.create}'s response too, for the same reason and for
     *                        consistency between the two methods' response shapes.
     */
    static JsonObject identityJson(Identity appIdentity, PeerId canonicalPeerId) {
        return JsonObject.builder()
                .put("peerId", canonicalPeerId.value())
                .put("displayName", appIdentity.displayName())
                .put("identityPublicKey", Base64.getEncoder().encodeToString(appIdentity.publicKey()))
                .put("createdAt", appIdentity.createdAt())
                .build();
    }

    static JsonObject contactJson(Contact contact) {
        return JsonObject.builder()
                .put("peerId", contact.peerId().value())
                .put("displayName", contact.displayName())
                .put("verified", contact.verified())
                .put("addedAt", contact.addedAt())
                .build();
    }

    static JsonArray contactsJson(List<Contact> contacts) {
        JsonArray.Builder builder = JsonArray.builder();
        for (Contact contact : contacts) {
            builder.add(contactJson(contact));
        }
        return builder.build();
    }

    static JsonObject conversationJson(Conversation conversation) {
        // "members" from §4's original domain-model sketch is deliberately omitted -- it does
        // not exist on core-storage.model.Conversation (see that record's own Javadoc for why:
        // real membership tracking is M5/M8 design work, not guessed at here), so there is
        // nothing to serialize even if this method wanted to include it.
        return JsonObject.builder()
                .put("conversationId", conversation.conversationId())
                .put("type", conversation.type().name())
                .put("name", conversation.name())
                .put("createdAt", conversation.createdAt())
                .build();
    }

    static JsonArray conversationsJson(List<Conversation> conversations) {
        JsonArray.Builder builder = JsonArray.builder();
        for (Conversation conversation : conversations) {
            builder.add(conversationJson(conversation));
        }
        return builder.build();
    }

    /** See class Javadoc for why the JSON key is {@code content}, not {@code plaintext}. */
    static JsonObject messageJson(Message message) {
        return JsonObject.builder()
                .put("messageId", message.messageId())
                .put("conversationId", message.conversationId())
                .put("senderPeerId", message.senderPeerId().value())
                .put("senderDeviceId", message.senderDeviceId().value())
                .put("hlcTimestamp", message.hlcTimestamp())
                .put("contentType", message.contentType())
                .put("content", new String(message.plaintext(), StandardCharsets.UTF_8))
                .put("state", message.state().name())
                .build();
    }

    static JsonArray messagesJson(List<Message> messages) {
        JsonArray.Builder builder = JsonArray.builder();
        for (Message message : messages) {
            builder.add(messageJson(message));
        }
        return builder.build();
    }

    /** The {@code network.status} shape — field-for-field against {@code docs/M6g-gap-analysis-and-plan.md §2.4}. */
    static JsonObject networkStatusJson(PeerId peerId, String displayName, String[] listenAddresses,
                                         boolean relayConnected, int connectedPeerCount) {
        JsonArray.Builder addresses = JsonArray.builder();
        for (String address : listenAddresses) {
            addresses.add(new JsonString(address));
        }
        return JsonObject.builder()
                .put("peerId", peerId.value())
                .put("displayName", displayName)
                .put("listenAddresses", addresses.build())
                .put("relayConnected", relayConnected)
                .put("connectedPeerCount", connectedPeerCount)
                .build();
    }

    /**
     * One entry of {@code network.connectedPeers} — field-for-field against {@code
     * docs/M6g-gap-analysis-and-plan.md §2.4}. {@code displayName} resolution (contact name, else
     * route's own cosmetic name, else {@code null}) is the caller's job ({@link JsonRpcRouter}),
     * not this method's — it needs both a {@link Contact} lookup and the {@link PeerRoute}
     * together, which this class deliberately doesn't fetch on its own (see class Javadoc: no
     * StorageService dependency here, mapping only).
     */
    static JsonObject connectedPeerJson(PeerId peerId, String displayName, long lastSeen, boolean hasSession) {
        return JsonObject.builder()
                .put("peerId", peerId.value())
                .put("displayName", displayName)
                .put("lastSeen", lastSeen)
                .put("hasSession", hasSession)
                .build();
    }

    static JsonValue emptyResult() {
        return JsonObject.of();
    }
}
