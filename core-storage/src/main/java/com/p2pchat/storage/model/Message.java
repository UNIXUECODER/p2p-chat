package com.p2pchat.storage.model;

import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;

/**
 * Mirrors the {@code messages} table (docs/architecture-spec.md §9) plus the {@code Message}
 * domain record sketched in §4 — reconciled where the two disagreed:
 * <ul>
 *   <li>{@code createdAt} is in the SQL schema but not §4's sketch; kept here since it's a real
 *       persisted column.</li>
 *   <li>{@code senderDeviceId} is in §4's sketch but wasn't a column in §9's schema text; the
 *       column was added in V001__init.sql (see the note there) rather than dropping the field.</li>
 * </ul>
 *
 * <p>{@code plaintext} is {@code byte[]} per §4 ("never serialized to wire"), but the backing
 * column ({@code plaintext_cache}) is TEXT — every content type this project currently defines
 * (text/plain, text/markdown, file-ref, system) is textual, so SqliteStorageService stores it as
 * UTF-8 text. If a genuinely binary content type is added later, this reconciliation needs
 * revisiting; it isn't yet, so it hasn't been.
 */
public record Message(
        String messageId,
        String conversationId,
        PeerId senderPeerId,
        DeviceId senderDeviceId,
        String hlcTimestamp,
        String contentType,
        byte[] plaintext,
        DeliveryState state,
        long createdAt
) {
}
