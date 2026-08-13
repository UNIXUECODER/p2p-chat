package com.p2pchat.messaging.wire;

import java.util.Objects;
import java.util.UUID;

/**
 * Acknowledges that one specific chat message arrived. Not defined anywhere in
 * docs/architecture-spec.md's §6 {@code .proto} sketch — {@code EnvelopeType.DELIVERY_RECEIPT}
 * exists there, but no corresponding payload message does. Same shape of gap M4b found and
 * filled for {@code FileChunkRequestPayload}.
 *
 * <p>Deliberately per-message, not watermark-style — unlike {@link ReadReceiptPayload}. A
 * message either physically arrived at this device or it didn't; there is no meaningful "I've
 * received everything up to X" batching the way there is for "I've read everything up to X" —
 * delivery is an event on a single message, read state is a running position in the
 * conversation. Conflating the two into one shape would make one of them awkward to express.
 *
 * @param conversationId which conversation the acknowledged message belongs to
 * @param messageId      the {@code messageId} of the {@link ChatMessagePayload} being
 *                        acknowledged as delivered — validated as a UUID, matching
 *                        {@code ChatMessagePayload.messageId}'s own validation.
 */
public record DeliveryReceiptPayload(
        String conversationId,
        String messageId
) implements ChatWireMessage {

    public DeliveryReceiptPayload {
        requireNonEmpty(conversationId, "conversationId");
        requireUuid(messageId, "messageId");
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
