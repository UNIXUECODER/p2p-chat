package com.p2pchat.relay;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One row of {@link RelaySpool}'s durable queue. {@code senderPeerId} is carried alongside the
 * payload, not inferred later, because a single target peer can have messages queued from
 * several different senders at once — reconstructing the eventual {@code DELIVER} frame needs
 * the specific sender each individual message actually came from, not whichever peer happens to
 * be online and fetching or forwarding it.
 */
public record SpooledMessage(long id, String senderPeerId, byte[] payload, Instant enqueuedAt) {

    public SpooledMessage {
        Objects.requireNonNull(senderPeerId, "senderPeerId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpooledMessage other)) return false;
        return id == other.id
                && senderPeerId.equals(other.senderPeerId)
                && Arrays.equals(payload, other.payload)
                && enqueuedAt.equals(other.enqueuedAt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, senderPeerId, enqueuedAt);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
