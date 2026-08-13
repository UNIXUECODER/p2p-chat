package com.p2pchat.storage.model;

/**
 * Mirrors the {@code conversations} table (docs/architecture-spec.md §9). Introduced now
 * (M4e) purely to close the {@code messages.conversation_id REFERENCES conversations} gap
 * flagged at the end of M4d — {@link com.p2pchat.storage.StorageService} previously had no
 * way to create a row in this table at all, so {@code saveMessage} always failed with a
 * foreign-key violation for a conversation that didn't already exist by some other means.
 *
 * <p><b>Deliberately narrower than §4's domain-record sketch.</b> §4's {@code Conversation}
 * record also carries {@code Set<PeerId> members}; this one does not. Membership lives in its
 * own table ({@code conversation_members}), has its own read/write access pattern, and —
 * critically — that access pattern is exactly the thing M5/M8's real messaging and group work
 * needs to design (who gets added when, how a DIRECT conversation's two members are seeded,
 * how a GROUP's roster changes over time). Guessing at that shape now, just to make this
 * record match §4 exactly, would mean designing it twice. This type exists to satisfy one
 * foreign key so {@code saveMessage} works; it is not the real conversation-management API.
 */
public record Conversation(
        String conversationId,
        ConversationType type,
        String name,
        long createdAt
) {
}
