package com.p2pchat.messaging.wire;

import com.p2pchat.messaging.HlcTimestamp;

import java.util.Objects;
import java.util.UUID;

/**
 * A chat message, matching {@code ChatMessagePayload} in docs/architecture-spec.md §6's
 * {@code .proto} sketch, plus two fields not in that sketch: {@link #messageId} and
 * {@link #hlcTimestamp} — and one more, {@link #senderAddress}, added after M5c's actual
 * two-process design work found the same gap M4c already found once for
 * {@code FileOfferPayload}.
 *
 * <p><b>Why {@code senderAddress} exists (added during M5c, correcting M5b):</b>
 * {@code PeerNetworkService.sendEnvelope}'s own Javadoc is explicit: <i>"a one-shot send: opens
 * a new stream for this call rather than reusing an existing one"</i> — it always dials a fresh
 * multiaddr, it never reuses an inbound connection. M5b's original reasoning for leaving
 * {@code sender_peer_id}/address off this payload was that a chat session is already a live,
 * established Double Ratchet <i>session</i> — true at the crypto layer ({@code
 * LibsignalSecureSessionService} does hold ratchet state across calls) but not at the network
 * layer, where a listener that only knows the sender's peer ID (all the receiving callback's
 * {@code sender} parameter provides) has no way to physically reply. Exactly the chicken-and-egg
 * bug M4c's own README section describes for {@code FileOfferPayload}, for the identical reason:
 * the fix is the same one — the sender already knows its own address
 * ({@code network.listenAddresses()}); it reports it here, inside the encrypted, authenticated
 * message, so the recipient never needs to be told in advance.
 *
 * <p>Unlike file transfer — which has one distinct "offer" message establishing the exchange,
 * with every later {@code FileChunkPayload} riding on a connection the offer already
 * established — chat has no separate handshake payload; any given {@code ChatMessagePayload}
 * could be the first message of a fresh connection (M5c's demo Mains are one-shot processes,
 * same as every other milestone's). So this field lives on every chat message, not just a
 * one-time "offer" the way {@code FileOfferPayload} could concentrate it.
 *
 * <p><b>Why those two moved here:</b> the sketch places {@code message_id} and
 * {@code hlc_timestamp} on the outer {@code Envelope} shell, not on individual payloads like
 * this one. That shell has never actually been built anywhere in this project — M2b/M2c/M4
 * all carry plaintext bytes with whatever structure the payload itself defines, nothing more
 * (core-network's own {@code EnvelopeProtocol} is a same-named but unrelated low-level libp2p
 * stream protocol carrying undifferentiated bytes, not this proto shell). This is exactly the
 * same situation M4b already resolved the same way: {@code FileOfferPayload} needed
 * {@code senderAddress}, which the sketch also implied belonged "elsewhere," and it was added
 * directly to the payload rather than inventing an outer shell to hold one field. Same reasoning
 * here — {@code message_id} (needed for de-dup, per the sketch's own comment on {@code Envelope})
 * and {@code hlc_timestamp} (needed for ordering) are both things chat's own definition requires
 * for correctness, so they live directly on the one payload type that actually needs them.
 *
 * <p><b>What stayed implicit, deliberately, rather than also being added here:</b>
 * {@code sender_device_id}. Out of v1 scope entirely — the {@code Envelope} sketch's own comment
 * says {@code "0" in v1; reserved for multi-device} — so the receiving side (M5c) constructs
 * {@code core.storage.model.Message} with {@code DeviceId.DEFAULT}, the same convention every
 * other milestone already uses. {@code sender_peer_id} itself stays implicit too — the
 * receiving callback's {@code sender} parameter already provides it; only the dialable
 * <i>address</i>, which nothing else provides, needed to be added.
 *
 * @param messageId          UUID, for de-duplication on receipt (M5d). Validated as a real UUID
 *                            at construction, not just structurally decoded off the wire.
 * @param senderAddress      the sender's own dialable multiaddr — see above.
 * @param hlcTimestamp       this message's causal timestamp — see {@code core-messaging}'s
 *                            {@link HlcTimestamp}. Encoded on the wire via its own
 *                            {@code toString()}/{@code parse()} round trip (M5a), not
 *                            reinvented here.
 * @param conversationId     which conversation this belongs to. Kept explicit on the wire —
 *                            unlike file transfer's {@code conversation_id}, which M4b left
 *                            implicit in "which session it arrived over" because nothing
 *                            downstream needed a concrete value — {@code messages.conversation_id}
 *                            is a real foreign key (M4e) that storage must be able to populate
 *                            without inventing a derivation scheme.
 * @param contentType         e.g. {@code "text/plain"}, {@code "text/markdown"}
 * @param content             raw message bytes; interpretation is {@code contentType}'s job
 * @param replyToMessageId    optional (nullable) — the {@code messageId} of the message being
 *                            replied to, if any. Validated as a UUID when present, for the same
 *                            reason {@code messageId} is.
 */
public record ChatMessagePayload(
        String messageId,
        String senderAddress,
        HlcTimestamp hlcTimestamp,
        String conversationId,
        String contentType,
        byte[] content,
        String replyToMessageId
) implements ChatWireMessage {

    public ChatMessagePayload {
        requireUuid(messageId, "messageId");
        requireNonEmpty(senderAddress, "senderAddress");
        Objects.requireNonNull(hlcTimestamp, "hlcTimestamp");
        requireNonEmpty(conversationId, "conversationId");
        requireNonEmpty(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        if (replyToMessageId != null) {
            requireUuid(replyToMessageId, "replyToMessageId");
        }
    }

    private static void requireNonEmpty(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
    }

    private static void requireUuid(String value, String fieldName) {
        requireNonEmpty(value, fieldName);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID, got: " + value, e);
        }
    }
}
