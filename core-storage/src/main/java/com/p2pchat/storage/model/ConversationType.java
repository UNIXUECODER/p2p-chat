package com.p2pchat.storage.model;

/** Type of a conversation. Matches conversations.type's CHECK constraint (docs/architecture-spec.md §9). */
public enum ConversationType {
    DIRECT,
    GROUP
}
