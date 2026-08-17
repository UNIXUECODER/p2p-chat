package com.p2pchat.messaging;

import java.time.Clock;
import java.time.Duration;
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
 *
 * <p><b>Amendment (pre-M6 cleanup pass):</b> the drift check flagged above as deferred is now
 * implemented — {@link #checkDrift}, plus {@link RemoteTimestampRejectedException}. Deliberately
 * NOT folded into {@link #update} itself: {@code update}'s algorithm is exactly Figure 4 of the
 * paper, transcribed and tested against it, and mixing a trust decision into that method would
 * make it simultaneously a causality primitive and a policy decision — two different kinds of
 * correctness that are much easier to reason about, and to test, kept apart. {@code checkDrift}
 * is a separate, side-effect-free, opt-in check a caller makes before calling {@code update} —
 * the trust boundary lives in the caller (e.g. {@code ChatListenerMain}/{@code ChatSenderMain}
 * call it before every {@code update}), not inside the pure algorithm. {@code update} itself is
 * completely unchanged by this amendment; every test written against it before this pass remains
 * valid unchanged.
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

    /**
     * A remote timestamp's physical component is too far ahead of this node's own physical clock
     * to be trusted. Carries enough detail to log the rejection clearly, per the policy this
     * exists to implement (see {@link HybridLogicalClock#checkDrift}).
     */
    public static final class RemoteTimestampRejectedException extends RuntimeException {
        private final long remotePhysical;
        private final long localPhysical;
        private final long driftMillis;
        private final long maxDriftMillis;

        RemoteTimestampRejectedException(long remotePhysical, long localPhysical, long driftMillis, long maxDriftMillis) {
            super("remote timestamp " + driftMillis + "ms ahead of local physical time (max allowed "
                    + maxDriftMillis + "ms) \u2014 remotePhysical=" + remotePhysical + ", localPhysical=" + localPhysical);
            this.remotePhysical = remotePhysical;
            this.localPhysical = localPhysical;
            this.driftMillis = driftMillis;
            this.maxDriftMillis = maxDriftMillis;
        }

        public long remotePhysical() {
            return remotePhysical;
        }

        public long localPhysical() {
            return localPhysical;
        }

        public long driftMillis() {
            return driftMillis;
        }

        public long maxDriftMillis() {
            return maxDriftMillis;
        }
    }

    /**
     * Default policy for {@link #checkDrift} when a caller doesn't supply its own bound. Five
     * minutes is deliberately generous — real consumer-device clocks can legitimately be off by
     * more than a few seconds without anything being wrong, and the goal here is catching a
     * remote timestamp that's implausibly far in the future (an attempt to inflate this node's
     * clock, or a badly broken peer), not enforcing tight synchronization. This is a policy
     * default, chosen and documented as such, not a rigorously derived constant — tune it if
     * real-world testing shows it's wrong in either direction.
     */
    public static final Duration DEFAULT_MAX_FUTURE_DRIFT = Duration.ofMinutes(5);

    /**
     * Checks whether {@code remote}'s physical component is within {@code maxFutureDrift} of this
     * node's own current physical time, throwing {@link RemoteTimestampRejectedException} if not.
     * Purely a check — does not read or modify this clock's state, and calling it never advances
     * anything. Intended use: call this immediately before {@link #update} for any timestamp that
     * arrived from a remote peer (never for a purely local value, which by definition can't have
     * this problem); on rejection, the caller should not call {@code update} with that value, and
     * should not process whatever event carried it, exactly as the pre-M6 cleanup pass's own
     * checklist item for this describes ("reject or quarantine... log the event clearly").
     *
     * <p>Deliberately only checks the future direction. A remote timestamp far in the PAST is not
     * a trust problem the way one far in the future is — {@code update}'s own {@code max()}-based
     * algorithm already handles a stale/slow peer correctly (this node's own physical time simply
     * dominates), and rejecting old-but-honest messages would be actively wrong.
     */
    public void checkDrift(HlcTimestamp remote, Duration maxFutureDrift) {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(maxFutureDrift, "maxFutureDrift");
        long pt = physicalClock.millis();
        long driftMillis = remote.physical() - pt;
        long maxDriftMillis = maxFutureDrift.toMillis();
        if (driftMillis > maxDriftMillis) {
            throw new RemoteTimestampRejectedException(remote.physical(), pt, driftMillis, maxDriftMillis);
        }
    }

    /** {@link #checkDrift(HlcTimestamp, Duration)} using {@link #DEFAULT_MAX_FUTURE_DRIFT}. */
    public void checkDrift(HlcTimestamp remote) {
        checkDrift(remote, DEFAULT_MAX_FUTURE_DRIFT);
    }
}
