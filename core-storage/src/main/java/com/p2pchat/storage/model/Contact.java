package com.p2pchat.storage.model;

import com.p2pchat.model.PeerId;

/** Mirrors the {@code contacts} table and the {@code Contact} domain record (docs/architecture-spec.md §4/§9). */
public record Contact(
        PeerId peerId,
        String displayName,
        boolean verified,
        long addedAt
) {
}
