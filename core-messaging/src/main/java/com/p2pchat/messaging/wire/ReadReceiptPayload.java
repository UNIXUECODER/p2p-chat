package com.p2pchat.messaging.wire;

import com.p2pchat.messaging.HlcTimestamp;

import java.util.Objects;

/**
 * Acknowledges that everything up to and including {@link #readUpToHlcTimestamp} in a
 * conversation has been read. Not defined anywhere in docs/architecture-spec.md's §6
 * {@code .proto} sketch — {@code EnvelopeType.READ_RECEIPT} exists there, but no corresponding
 * payload message does. Same shape of gap M4b found and filled for
 * {@code FileChunkRequestPayload}; discussed explicitly before building this one, given the
 * real design fork involved (see below).
 *
 * <p><b>Watermark-style, not per-message like {@link DeliveryReceiptPayload}.</b> Real chat
 * apps read receipts almost universally work this way — one receipt covers everything someone
 * just looked at in one sitting, not one wire message per chat message read. It's also the
 * natural fit given {@link HlcTimestamp}'s whole purpose is exactly this kind of "everything up
 * to this point" comparison, and given {@code messages}' schema (docs/architecture-spec.md §9)
 * already indexes {@code (conversation_id, hlc_timestamp)} — {@code WHERE conversation_id = ?
 * AND hlc_timestamp <= ?} is a direct, efficient use of that index, not an awkward retrofit.
 *
 * @param conversationId       which conversation this applies to
 * @param readUpToHlcTimestamp the watermark — every message in this conversation with an
 *                              {@code hlc_timestamp} at or before this value is considered read
 */
public record ReadReceiptPayload(
        String conversationId,
        HlcTimestamp readUpToHlcTimestamp
) implements ChatWireMessage {

    public ReadReceiptPayload {
        Objects.requireNonNull(conversationId, "conversationId");
        if (conversationId.isEmpty()) {
            throw new IllegalArgumentException("conversationId must not be empty");
        }
        Objects.requireNonNull(readUpToHlcTimestamp, "readUpToHlcTimestamp");
    }
}
