package com.p2pchat.relay;

/**
 * Track A-3 (pre-m6h-hardening-plan.md), Commit 3: what actually happened when {@link
 * RelaySpool#enqueue} was called. Commit 2's {@code enqueue} unconditionally accepted every
 * call — this exists because that's no longer true once a per-peer quota and a per-frame size
 * ceiling are real: a caller needs to know whether its message is actually safely queued, or was
 * silently dropped, and if dropped, which of the two independent limits caused it (worth telling
 * apart in a log line — one means "this target's queue is full," the other means "this frame
 * should never have been offered to the spool at all").
 */
public enum EnqueueResult {

    /** The message was written to the spool and will be delivered once the target reconnects. */
    ACCEPTED,

    /**
     * Rejected: {@code targetPeerId} already has {@code maxMessagesPerPeer} messages queued, or
     * accepting this one would push its total queued bytes past {@code maxBytesPerPeer}. Not
     * persisted at all.
     */
    REJECTED_QUOTA_EXCEEDED,

    /**
     * Rejected: {@code payload} alone exceeds the spool's max single-frame size, checked before
     * the quota (and before touching the database at all — see {@link RelaySpool#enqueue}'s own
     * Javadoc for why a real client should never legitimately hit this).
     */
    REJECTED_MESSAGE_TOO_LARGE
}
