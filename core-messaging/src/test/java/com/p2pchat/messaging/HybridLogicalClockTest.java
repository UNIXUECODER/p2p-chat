package com.p2pchat.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each test names the exact branch of Figure 4 (docs/architecture-spec.md §11, and
 * {@link HybridLogicalClock}'s own Javadoc) it exercises, so a future change that breaks one
 * specific case is easy to trace back to the paper.
 */
class HybridLogicalClockTest {

    // --- "Send or local event" ---------------------------------------------------------------

    @Test
    void localEventAdvancesPhysicalWhenClockMovesForward() {
        MutableTestClock clock = new MutableTestClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        HlcTimestamp first = hlc.now();
        assertThat(first.physical()).isEqualTo(1000L);
        assertThat(first.counter()).isZero(); // l.j != l'.j (0) on the very first event -> counter resets to 0

        clock.set(2000L);
        HlcTimestamp second = hlc.now();
        assertThat(second.physical()).isEqualTo(2000L);
        assertThat(second.counter()).isZero();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void localEventIncrementsCounterWhenPhysicalClockIsFrozen() {
        MutableTestClock clock = new MutableTestClock(1000L); // never advances in this test
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        HlcTimestamp first = hlc.now();
        HlcTimestamp second = hlc.now();
        HlcTimestamp third = hlc.now();

        assertThat(first.physical()).isEqualTo(1000L);
        assertThat(second.physical()).isEqualTo(1000L);
        assertThat(third.physical()).isEqualTo(1000L);
        assertThat(first.counter()).isEqualTo(0);
        assertThat(second.counter()).isEqualTo(1);
        assertThat(third.counter()).isEqualTo(2);
    }

    // --- "Receive event of message m" — all four branches -------------------------------------

    @Test
    void receiveBranch_allThreeEqual_takesMaxCounterPlusOne() {
        MutableTestClock clock = new MutableTestClock(7000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        HlcTimestamp local = hlc.now(); // (7000, 0)

        clock.set(1000L); // pt now behind both, so it can't dominate the max()
        HlcTimestamp remote = new HlcTimestamp(7000L, 3, "remote-node"); // l.m == l.j == l'.j == 7000

        HlcTimestamp result = hlc.update(remote);
        assertThat(result.physical()).isEqualTo(7000L);
        assertThat(result.counter()).isEqualTo(Math.max(local.counter(), remote.counter()) + 1); // max(0,3)+1 = 4
    }

    @Test
    void receiveBranch_localPhysicalDominates_incrementsLocalCounter() {
        MutableTestClock clock = new MutableTestClock(5000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        hlc.now(); // local state: (5000, 0)

        clock.set(1000L); // pt behind local
        HlcTimestamp remote = new HlcTimestamp(3000L, 7, "remote-node"); // older than local

        HlcTimestamp result = hlc.update(remote);
        assertThat(result.physical()).isEqualTo(5000L); // local l dominates
        assertThat(result.counter()).isEqualTo(1);       // local c (0) + 1
    }

    @Test
    void receiveBranch_remotePhysicalDominates_incrementsRemoteCounter() {
        MutableTestClock clock = new MutableTestClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        hlc.now(); // local state: (1000, 0)

        clock.set(2000L); // pt still behind remote
        HlcTimestamp remote = new HlcTimestamp(9000L, 4, "remote-node"); // newer than local

        HlcTimestamp result = hlc.update(remote);
        assertThat(result.physical()).isEqualTo(9000L); // remote l dominates
        assertThat(result.counter()).isEqualTo(5);       // remote c (4) + 1
    }

    @Test
    void receiveBranch_physicalClockDominatesBoth_resetsCounterToZero() {
        MutableTestClock clock = new MutableTestClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        hlc.now(); // local state: (1000, 0)

        HlcTimestamp remote = new HlcTimestamp(2000L, 5, "remote-node");
        clock.set(9000L); // pt now ahead of both local (1000) and remote (2000)

        HlcTimestamp result = hlc.update(remote);
        assertThat(result.physical()).isEqualTo(9000L); // pt dominates
        assertThat(result.counter()).isZero();
    }

    // --- Causality: Theorem 1 (e hb f ⟹ (l.e,c.e) < (l.f,c.f)) --------------------------------

    @Test
    void receivingAMessageAlwaysProducesATimestampGreaterThanTheSentOne() {
        // A sends; B receives A's message. Regardless of B's own local state or physical clock,
        // the receive event's timestamp must be strictly greater than what was sent — this is
        // Theorem 1's core guarantee, and it's what message ordering in storage depends on.
        MutableTestClock clockA = new MutableTestClock(500L);
        MutableTestClock clockB = new MutableTestClock(100L); // B's physical clock lags A's
        HybridLogicalClock a = new HybridLogicalClock("node-a", clockA);
        HybridLogicalClock b = new HybridLogicalClock("node-b", clockB);

        HlcTimestamp sent = a.now();
        HlcTimestamp received = b.update(sent);

        assertThat(received).isGreaterThan(sent);
    }

    @Test
    void causalityIsTransitiveAcrossAChainOfThreeNodes() {
        MutableTestClock clockA = new MutableTestClock(100L);
        MutableTestClock clockB = new MutableTestClock(50L);  // lags A
        MutableTestClock clockC = new MutableTestClock(10L);  // lags B
        HybridLogicalClock a = new HybridLogicalClock("node-a", clockA);
        HybridLogicalClock b = new HybridLogicalClock("node-b", clockB);
        HybridLogicalClock c = new HybridLogicalClock("node-c", clockC);

        HlcTimestamp e = a.now();          // A: local send event
        HlcTimestamp f = b.update(e);      // B: receives from A
        HlcTimestamp g = b.now();          // B: local event after receiving (e.g. forwards to C)
        HlcTimestamp h = c.update(g);      // C: receives from B

        assertThat(e).isLessThan(f);
        assertThat(f).isLessThan(g);
        assertThat(g).isLessThan(h);
        assertThat(e).isLessThan(h); // transitivity end-to-end
    }

    // --- The property Figure 3/5 illustrate: counter growth isn't unbounded once pt catches up ---

    @Test
    void counterGrowsWhilePhysicalClockIsFrozenThenResetsOncePtCatchesUp() {
        MutableTestClock clock = new MutableTestClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        HlcTimestamp last = hlc.now();
        for (int i = 0; i < 50; i++) {
            last = hlc.now(); // pt frozen the whole time -> counter climbs every call
        }
        assertThat(last.physical()).isEqualTo(1000L);
        assertThat(last.counter()).isEqualTo(50); // grew, as the paper's naive-algorithm counterexample warns it can

        clock.set(1_000_000L); // pt now far ahead of the inflated l
        HlcTimestamp afterCatchUp = hlc.now();
        assertThat(afterCatchUp.physical()).isEqualTo(1_000_000L);
        assertThat(afterCatchUp.counter()).isZero(); // reset — this is exactly what Figure 4 fixes vs. the naive algorithm
    }

    @Test
    void counterStaysBoundedAcrossACausalCycleOfLaggingNodes() {
        // Mirrors the shape of the paper's own counterexample (§3.2, Figures 3 and 5): a cycle
        // of causally-chained receives among nodes whose physical clocks are all frozen inflates
        // the counter each time around the loop — then once physical time catches up, it resets.
        // (Built from scratch, not the paper's literal figures — those are diagrams with specific
        // numeric traces this text description doesn't fully specify — but it exercises the same
        // property the figures are making the point about.)
        MutableTestClock clockA = new MutableTestClock(1000L);
        MutableTestClock clockB = new MutableTestClock(1000L);
        MutableTestClock clockC = new MutableTestClock(1000L);
        HybridLogicalClock a = new HybridLogicalClock("node-a", clockA);
        HybridLogicalClock b = new HybridLogicalClock("node-b", clockB);
        HybridLogicalClock c = new HybridLogicalClock("node-c", clockC);

        HlcTimestamp msg = a.now();
        int previousCounter = -1;
        for (int lap = 0; lap < 10; lap++) {
            HlcTimestamp atB = b.update(msg);
            HlcTimestamp atC = c.update(atB);
            msg = a.update(atC); // completes the cycle back at A

            assertThat(msg.physical()).isEqualTo(1000L); // pt frozen everywhere -> l never advances
            assertThat(msg.counter()).isGreaterThan(previousCounter); // strictly grows each lap, as the paper warns
            previousCounter = msg.counter();
        }

        // Now let every node's physical clock catch up past the inflated l, exactly as the fix
        // in Figure 4/5 intends. One more local event per node should reset straight to pt.
        clockA.set(5_000_000L);
        clockB.set(5_000_000L);
        clockC.set(5_000_000L);

        HlcTimestamp resetA = a.now();
        assertThat(resetA.physical()).isEqualTo(5_000_000L);
        assertThat(resetA.counter()).isZero();
    }

    // --- Thread safety: not assumed, actually stress-tested ------------------------------------

    @Test
    void concurrentLocalEventsNeverLoseAnUpdate() throws InterruptedException {
        // If the compare-and-swap loop in now() were broken, concurrent callers could read the
        // same prev state and both "win", producing duplicate timestamps — a silent violation of
        // HLC's own uniqueness/monotonicity guarantee. Proving the CAS loop is correct requires
        // actually racing threads against it, not just reading the code and asserting it's fine.
        int threadCount = 32;
        int callsPerThread = 300;
        int totalCalls = threadCount * callsPerThread;

        MutableTestClock clock = new MutableTestClock(1000L); // frozen: forces every call through
        // the "increment counter" branch, the branch most exposed to a lost-update race.
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        Set<HlcTimestamp> results = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLine.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        results.add(hlc.now());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startLine.countDown(); // release all threads at once, maximize actual contention
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("all threads completed within the timeout").isTrue();
        // The real assertion: exactly totalCalls DISTINCT timestamps came out. A ConcurrentHashMap
        // Set silently absorbs duplicates via equals/hashCode, so if the CAS loop ever let two
        // threads win with the same (physical, counter), this count would be lower than totalCalls.
        assertThat(results).hasSize(totalCalls);

        // And they should form a contiguous 0..totalCalls-1 counter sequence at the same physical
        // time (clock was frozen), confirming no gaps either — every CAS attempt that failed was
        // correctly retried, not silently dropped.
        int[] counters = results.stream().mapToInt(HlcTimestamp::counter).sorted().toArray();
        for (int i = 0; i < counters.length; i++) {
            assertThat(counters[i]).isEqualTo(i);
        }
    }

    // --- Pre-M6 cleanup pass: checkDrift, the trust-boundary guard flagged as deferred in this
    // class's own Javadoc since M5a ("Left for whichever of M5b/M5c first has update fed an
    // untrusted remote value"). Deliberately a separate opt-in check, not a change to update()
    // itself — see the Javadoc amendment for why — so these tests never call update() at all. ---

    @Test
    void checkDriftAcceptsARemoteTimestampWithinTheDefaultBound() {
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp remote = new HlcTimestamp(1_000_000L + Duration.ofSeconds(30).toMillis(), 0, "node-b");

        hlc.checkDrift(remote); // must not throw
    }

    @Test
    void checkDriftAcceptsARemoteTimestampExactlyAtACustomBoundary() {
        // drift == max is accepted; only STRICTLY beyond the bound is rejected.
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp remote = new HlcTimestamp(1_000_000L + 60_000L, 0, "node-b");

        hlc.checkDrift(remote, Duration.ofSeconds(60)); // must not throw
    }

    @Test
    void checkDriftRejectsARemoteTimestampOneMillisecondBeyondTheBound() {
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp remote = new HlcTimestamp(1_000_000L + 60_001L, 0, "node-b");

        assertThatThrownBy(() -> hlc.checkDrift(remote, Duration.ofSeconds(60)))
                .isInstanceOf(HybridLogicalClock.RemoteTimestampRejectedException.class);
    }

    @Test
    void checkDriftRejectsARemoteTimestampFarBeyondTheDefaultFiveMinuteBound() {
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp remote = new HlcTimestamp(1_000_000L + Duration.ofHours(1).toMillis(), 0, "node-b");

        assertThatThrownBy(() -> hlc.checkDrift(remote))
                .isInstanceOf(HybridLogicalClock.RemoteTimestampRejectedException.class);
    }

    @Test
    void checkDriftRejectionExceptionCarriesTheDetailNeededToLogItClearly() {
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp remote = new HlcTimestamp(1_000_000L + 100_000L, 0, "node-b");

        assertThatThrownBy(() -> hlc.checkDrift(remote, Duration.ofSeconds(60)))
                .isInstanceOf(HybridLogicalClock.RemoteTimestampRejectedException.class)
                .satisfies(e -> {
                    HybridLogicalClock.RemoteTimestampRejectedException rejected =
                            (HybridLogicalClock.RemoteTimestampRejectedException) e;
                    assertThat(rejected.remotePhysical()).isEqualTo(1_100_000L);
                    assertThat(rejected.localPhysical()).isEqualTo(1_000_000L);
                    assertThat(rejected.driftMillis()).isEqualTo(100_000L);
                    assertThat(rejected.maxDriftMillis()).isEqualTo(60_000L);
                });
    }

    @Test
    void checkDriftNeverRejectsARemoteTimestampInThePast() {
        // Only the future direction is a trust problem — update()'s own max()-based algorithm
        // already handles a stale/slow peer correctly. See checkDrift's own Javadoc.
        MutableTestClock clock = new MutableTestClock(10_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp remote = new HlcTimestamp(0L, 0, "node-b"); // as far in the past as a timestamp can be

        hlc.checkDrift(remote); // must not throw
    }

    @Test
    void checkDriftDoesNotMutateClockState() {
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);
        HlcTimestamp before = hlc.now();

        hlc.checkDrift(new HlcTimestamp(1_000_500L, 0, "node-b")); // within bound, must not throw or mutate

        HlcTimestamp after = hlc.now();
        // Physical time frozen (clock never advanced) -> if checkDrift left state untouched,
        // this is a plain counter increment, identical to calling now() twice with nothing
        // between them.
        assertThat(after.physical()).isEqualTo(before.physical());
        assertThat(after.counter()).isEqualTo(before.counter() + 1);
    }
}
