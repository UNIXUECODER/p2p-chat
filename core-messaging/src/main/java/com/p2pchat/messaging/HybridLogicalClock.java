package com.p2pchat.messaging;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One node's hybrid logical clock — directly implements the two event-handling procedures from
 * Kulkarni, Demirbas, Madeppa, Avva, Leone, <i>"Logical Physical Clocks"</i> (OPODIS 2014 / SUNY
 * Buffalo Tech Report 2014-04), Figure 4. Both are transcribed here for anyone checking this
 * against the paper directly:
 *
 * <pre>
 * Initially l.j := 0; c.j := 0
 *
 * Send or local event                          Receive event of message m
 *   l'.j := l.j                                   l'.j := l.j
 *   l.j  := max(l'.j, pt.j)                        l.j  := max(l'.j, l.m, pt.j)
 *   if (l.j == l'.j) then c.j := c.j + 1           if      (l.j==l'.j==l.m) then c.j := max(c.j,c.m)+1
 *   else c.j := 0                                  elseif  (l.j==l'.j)      then c.j := c.j+1
 *                                                   elseif  (l.j==l.m)       then c.j := c.m+1
 *                                                   else                          c.j := 0
 * </pre>
 *
 * {@link #now()} is "Send or local event"; {@link #update(HlcTimestamp)} is "Receive event of
 * message m". Comparison {@code (a,b) < (c,d) iff a<c or (a=c and b<d)} lives on
 * {@link HlcTimestamp#compareTo}, not here.
 *
 * <p><b>What this class adds on top of the paper — and, just as importantly, what it doesn't:</b>
 * <ul>
 *   <li><b>Adds:</b> thread safety. The paper's algorithm is written for one sequential thread
 *   of events per node; this project's actual use is one {@code HybridLogicalClock} instance
 *   shared across however many peer sessions a node has running concurrently (M6's daemon holds
 *   multiple simultaneous sessions — see the M6 milestone note in README.md). A per-node clock
 *   that lost updates under concurrent calls would silently violate the paper's own causality
 *   guarantee, so this isn't optional hardening added after the fact the way M3c's TOCTOU fix
 *   was — it's built in from the start via a compare-and-swap loop on an immutable
 *   {@link HlcTimestamp}, the same {@code AtomicReference}-based idiom M3c already established
 *   in {@code core-network}'s {@code Initiator}.</li>
 *   <li><b>Deliberately not added:</b> the paper's §4 resilience extensions — bounded {@code
 *   l - pt} drift checks, rejecting remote timestamps that are implausibly far ahead, and
 *   self-stabilization after state corruption. Those matter once this is actually facing
 *   untrusted values arriving over the network from a peer (a malicious or buggy peer could send
 *   a {@code physical} value far in the future to try to inflate everyone's clock); they don't
 *   matter yet for proving the core algorithm correct in isolation, which is all M5a is. Left
 *   for whichever of M5b/M5c first has {@link #update} fed an untrusted remote value — flagged
 *   here so that milestone doesn't have to rediscover the gap.</li>
 * </ul>
 */
public final class HybridLogicalClock {

    private final String nodeId;
    private final Clock physicalClock;
    private final AtomicReference<HlcTimestamp> state;

    public HybridLogicalClock(String nodeId) {
        this(nodeId, Clock.systemUTC());
    }

    /** Package-private-in-spirit constructor for tests: inject a deterministic {@link Clock}. */
    public HybridLogicalClock(String nodeId, Clock physicalClock) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.physicalClock = Objects.requireNonNull(physicalClock, "physicalClock");
        this.state = new AtomicReference<>(new HlcTimestamp(0L, 0, nodeId));
    }

    /**
     * "Send or local event" (Figure 4, left column). Call this to timestamp a locally-originated
     * event — e.g. a chat message this node is sending.
     */
    public HlcTimestamp now() {
        HlcTimestamp prev;
        HlcTimestamp next;
        do {
            prev = state.get();
            long pt = physicalClock.millis();
            long newPhysical = Math.max(prev.physical(), pt);
            int newCounter = (newPhysical == prev.physical()) ? prev.counter() + 1 : 0;
            next = new HlcTimestamp(newPhysical, newCounter, nodeId);
        } while (!state.compareAndSet(prev, next));
        return next;
    }

    /**
     * "Receive event of message m" (Figure 4, right column). Call this when a timestamped event
     * arrives from a remote peer — e.g. a chat message this node just received — <i>before</i>
     * this node's own clock is used to timestamp anything caused by it (replies, receipts). The
     * returned value is this node's own new local timestamp for the receive event itself, not a
     * copy of {@code remote}.
     */
    public HlcTimestamp update(HlcTimestamp remote) {
        Objects.requireNonNull(remote, "remote");
        HlcTimestamp prev;
        HlcTimestamp next;
        do {
            prev = state.get();
            long pt = physicalClock.millis();
            long newPhysical = Math.max(Math.max(prev.physical(), remote.physical()), pt);

            int newCounter;
            if (newPhysical == prev.physical() && newPhysical == remote.physical()) {
                newCounter = Math.max(prev.counter(), remote.counter()) + 1;
            } else if (newPhysical == prev.physical()) {
                newCounter = prev.counter() + 1;
            } else if (newPhysical == remote.physical()) {
                newCounter = remote.counter() + 1;
            } else {
                newCounter = 0;
            }
            next = new HlcTimestamp(newPhysical, newCounter, nodeId);
        } while (!state.compareAndSet(prev, next));
        return next;
    }
}
