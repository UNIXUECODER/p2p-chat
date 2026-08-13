package com.p2pchat.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Clock} whose reading can be set/advanced explicitly from a test, so
 * {@code HybridLogicalClock} scenarios ("physical time is frozen", "physical time jumps ahead")
 * are deterministic instead of racing the real wall clock. {@link #millis()} reads through
 * {@link AtomicLong} so it's safe to share across threads in the concurrency test.
 */
final class MutableTestClock extends Clock {

    private final AtomicLong millis;

    MutableTestClock(long initialMillis) {
        this.millis = new AtomicLong(initialMillis);
    }

    void set(long newMillis) {
        millis.set(newMillis);
    }

    void advance(long deltaMillis) {
        millis.addAndGet(deltaMillis);
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
        throw new UnsupportedOperationException("not needed for these tests");
    }
}
