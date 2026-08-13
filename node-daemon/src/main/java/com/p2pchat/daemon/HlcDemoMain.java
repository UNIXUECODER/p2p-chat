package com.p2pchat.daemon;

import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.HybridLogicalClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M5a: proves {@code HybridLogicalClock} — both event procedures from Kulkarni et al. (OPODIS
 * 2014) Figure 4, causality across a chain of nodes, the drift/reset property Figures 3/5
 * illustrate, and thread safety under real concurrent load — in isolation, before any of it is
 * wired to networking or storage. Same "prove the primitive standalone" pattern every prior
 * milestone piece used (M2a's {@code runCryptoDemo}, M4a's {@code runFileTransferDemo}).
 *
 * <p><b>Added after the fact, not alongside M5a originally</b> — every other milestone from M0
 * through M4d has exactly this kind of standalone, human-runnable demo; M5a shipped with only
 * {@code ./gradlew :core-messaging:test} and no equivalent here, an inconsistency caught while
 * building M5b's own demo and corrected retroactively. See the M5a section of README.md.
 */
public class HlcDemoMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testLocalEventAdvancesWithClock();
        testLocalEventFrozenClockIncrementsCounter();
        testReceiveBranchAllThreeEqual();
        testReceiveBranchLocalDominates();
        testReceiveBranchRemoteDominates();
        testReceiveBranchPhysicalClockDominatesBoth();
        testCausalityChainAcrossThreeNodes();
        testCounterGrowsThenResetsAfterClockCatchesUp();
        testConcurrentLocalEventsNeverLoseAnUpdate();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.out.println();
        System.out.println(failed == 0
                ? "M5a CONFIRMED: HybridLogicalClock implements Kulkarni et al. Figure 4 correctly, including causality, drift/reset, and thread safety under load."
                : "M5a FAILED: see the [FAIL] lines above.");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testLocalEventAdvancesWithClock() {
        MutableClock clock = new MutableClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        HlcTimestamp first = hlc.now();
        check("First local event: physical == clock reading", first.physical() == 1000L);
        check("First local event: counter starts at 0", first.counter() == 0);

        clock.set(2000L);
        HlcTimestamp second = hlc.now();
        check("Second local event: physical advances with clock", second.physical() == 2000L);
        check("Second local event: is greater than the first", second.compareTo(first) > 0);
    }

    private static void testLocalEventFrozenClockIncrementsCounter() {
        MutableClock clock = new MutableClock(1000L); // never advances
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        HlcTimestamp t0 = hlc.now();
        HlcTimestamp t1 = hlc.now();
        HlcTimestamp t2 = hlc.now();

        check("Frozen clock: physical stays constant", t0.physical() == 1000L && t1.physical() == 1000L && t2.physical() == 1000L);
        check("Frozen clock: counter increments 0,1,2", t0.counter() == 0 && t1.counter() == 1 && t2.counter() == 2);
    }

    private static void testReceiveBranchAllThreeEqual() {
        MutableClock clock = new MutableClock(7000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        HlcTimestamp local = hlc.now(); // (7000, 0)

        clock.set(1000L); // pt behind both, can't dominate
        HlcTimestamp remote = new HlcTimestamp(7000L, 3, "remote-node"); // l.m == l.j == l'.j

        HlcTimestamp result = hlc.update(remote);
        check("Receive, all three physical values equal: physical unchanged", result.physical() == 7000L);
        check("Receive, all three physical values equal: counter = max(local,remote)+1",
                result.counter() == Math.max(local.counter(), remote.counter()) + 1);
    }

    private static void testReceiveBranchLocalDominates() {
        MutableClock clock = new MutableClock(5000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        hlc.now();

        clock.set(1000L);
        HlcTimestamp remote = new HlcTimestamp(3000L, 7, "remote-node"); // older than local

        HlcTimestamp result = hlc.update(remote);
        check("Receive, local physical dominates: physical stays local's", result.physical() == 5000L);
        check("Receive, local physical dominates: counter = local.counter + 1", result.counter() == 1);
    }

    private static void testReceiveBranchRemoteDominates() {
        MutableClock clock = new MutableClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        hlc.now();

        clock.set(2000L);
        HlcTimestamp remote = new HlcTimestamp(9000L, 4, "remote-node"); // newer than local

        HlcTimestamp result = hlc.update(remote);
        check("Receive, remote physical dominates: physical becomes remote's", result.physical() == 9000L);
        check("Receive, remote physical dominates: counter = remote.counter + 1", result.counter() == 5);
    }

    private static void testReceiveBranchPhysicalClockDominatesBoth() {
        MutableClock clock = new MutableClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("local-node", clock);
        hlc.now();

        HlcTimestamp remote = new HlcTimestamp(2000L, 5, "remote-node");
        clock.set(9000L); // pt ahead of both local (1000) and remote (2000)

        HlcTimestamp result = hlc.update(remote);
        check("Receive, wall clock dominates both: physical becomes pt", result.physical() == 9000L);
        check("Receive, wall clock dominates both: counter resets to 0", result.counter() == 0);
    }

    private static void testCausalityChainAcrossThreeNodes() {
        MutableClock clockA = new MutableClock(100L);
        MutableClock clockB = new MutableClock(50L);
        MutableClock clockC = new MutableClock(10L);
        HybridLogicalClock a = new HybridLogicalClock("node-a", clockA);
        HybridLogicalClock b = new HybridLogicalClock("node-b", clockB);
        HybridLogicalClock c = new HybridLogicalClock("node-c", clockC);

        HlcTimestamp e = a.now();
        HlcTimestamp f = b.update(e);
        HlcTimestamp g = b.now();
        HlcTimestamp h = c.update(g);

        check("Causality chain: e < f < g < h", e.compareTo(f) < 0 && f.compareTo(g) < 0 && g.compareTo(h) < 0);
        check("Causality chain: transitivity end-to-end (e < h)", e.compareTo(h) < 0);
    }

    private static void testCounterGrowsThenResetsAfterClockCatchesUp() {
        MutableClock clock = new MutableClock(1000L);
        HybridLogicalClock hlc = new HybridLogicalClock("node-a", clock);

        HlcTimestamp last = hlc.now();
        for (int i = 0; i < 50; i++) {
            last = hlc.now(); // frozen clock — counter climbs every call
        }
        check("Counter grows to 50 while physical clock is frozen", last.physical() == 1000L && last.counter() == 50);

        clock.set(1_000_000L); // catches up past the inflated counter
        HlcTimestamp afterCatchUp = hlc.now();
        check("Counter resets to 0 once physical clock catches up", afterCatchUp.physical() == 1_000_000L && afterCatchUp.counter() == 0);
    }

    private static void testConcurrentLocalEventsNeverLoseAnUpdate() {
        int threadCount = 32;
        int callsPerThread = 300;
        int totalCalls = threadCount * callsPerThread;

        MutableClock clock = new MutableClock(1000L); // frozen — forces every call through the
        // counter-increment branch, the one most exposed to a lost-update race.
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

        startLine.countDown();
        boolean finished;
        try {
            finished = done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        pool.shutdown();

        check("Concurrency: all " + threadCount + " threads finished within 30s", finished);
        check("Concurrency: " + totalCalls + " calls produced " + totalCalls + " distinct timestamps (no lost updates)",
                results.size() == totalCalls);
    }

    /** Mirrors {@code core-messaging}'s test-only {@code MutableTestClock} — kept separate since demo Mains don't depend on test sourcesets. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void set(long newMillis) {
            millis.set(newMillis);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("not needed for this demo");
        }
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + description);
        } else {
            failed++;
            System.out.println("[FAIL] " + description);
        }
    }
}
