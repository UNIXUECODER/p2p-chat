package com.p2pchat.messaging.wire;

/**
 * The three chat message kinds that travel as the plaintext of an {@code EncryptedFrame} once
 * decrypted via core-crypto's {@code SecureSessionService} — the same role
 * {@code core-filetransfer.wire.FileTransferMessage} has played for file transfer since M4b.
 *
 * <p>Reuses the exact marker values docs/architecture-spec.md §6's {@code EnvelopeType} enum
 * already assigned ({@code CHAT_MESSAGE=2}, {@code DELIVERY_RECEIPT=3}, {@code READ_RECEIPT=4})
 * — see {@link ChatMessageCodec}.
 *
 * <p><b>This is its own sealed hierarchy, not a merge with {@code FileTransferMessage}.</b>
 * {@code FileTransferMessage}'s own Javadoc named this exact fork in the road: unifying chat and
 * file-transfer message kinds under one shared dispatch mechanism was deferred "until M5 gives a
 * second real consumer." M5 is that consumer now, but the actual need for shared dispatch —
 * one decrypted byte stream that could be *either* kind, requiring something to ask "which one is
 * this?" — doesn't exist yet either. Every M4 demo is file-transfer-only; M5's demos (M5c) will
 * be chat-only. That need only becomes real when a single live session has to field both at once,
 * which is M6's daemon, not this milestone. So: two independent sealed hierarchies, each
 * self-contained, each with its own codec — not a retrofit of M4's already-proven code, and not
 * a shared abstraction built for a caller that doesn't exist yet.
 */
public sealed interface ChatWireMessage
        permits ChatMessagePayload, DeliveryReceiptPayload, ReadReceiptPayload {
}
