package com.p2pchat.relay;

import com.p2pchat.storage.MigrationRunner;
import com.p2pchat.storage.SqliteDatabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track A-3 (pre-m6h-hardening-plan.md), Commit 2: basic enqueue/fetch/delete shape for {@link
 * SqliteRelaySpool}, against a real SQLite file via the same {@link SqliteDatabase} /
 * {@link MigrationRunner} machinery {@code core-storage} itself uses — not a fake, since the
 * whole point of this class existing is the real thing (real file permissions, a real second,
 * independent {@code schema_migrations}-tracked schema, real SQLite concurrency behavior under
 * genuinely simultaneous writers). Quota, TTL, and max-frame-size enforcement are deliberately
 * out of scope here — see {@link RelaySpool}'s own Javadoc for why this commit's shape is
 * enqueue/fetch/delete only.
 */
class SqliteRelaySpoolTest {

    private SqliteDatabase database;
    private SqliteRelaySpool spool;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        database = SqliteDatabase.openOrCreate(tempDir, "relay-spool.sqlite", relayMigrations);
        spool = new SqliteRelaySpool(database.connection());
    }

    @AfterEach
    void tearDown() throws Exception {
        spool.close();
        database.close();
    }

    @Test
    void enqueuedMessageIsFetchedBackWithItsSenderAndPayloadIntact() {
        spool.enqueue("bob", "alice", "hello bob".getBytes(StandardCharsets.UTF_8)).join();

        List<SpooledMessage> forBob = spool.fetchForTarget("bob").join();

        assertThat(forBob).hasSize(1);
        assertThat(forBob.get(0).senderPeerId()).isEqualTo("alice");
        assertThat(forBob.get(0).payload()).isEqualTo("hello bob".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deletedMessageNoLongerAppearsInFetch() {
        spool.enqueue("bob", "alice", "hello bob".getBytes(StandardCharsets.UTF_8)).join();
        long id = spool.fetchForTarget("bob").join().get(0).id();

        spool.delete(id).join();

        assertThat(spool.fetchForTarget("bob").join()).isEmpty();
    }

    @Test
    void fetchDoesNotDeleteAnything() {
        spool.enqueue("bob", "alice", "still here".getBytes(StandardCharsets.UTF_8)).join();

        spool.fetchForTarget("bob").join();
        spool.fetchForTarget("bob").join();

        // Same message, fetched twice, still present both times -- fetch and delete are
        // deliberately separate primitives (see RelaySpool's own Javadoc for why).
        assertThat(spool.fetchForTarget("bob").join()).hasSize(1);
    }

    @Test
    void multipleMessagesFromDifferentSendersPreserveFifoOrderAndEachOwnSender() {
        spool.enqueue("bob", "alice", "msg1".getBytes(StandardCharsets.UTF_8)).join();
        spool.enqueue("bob", "carol", "msg2".getBytes(StandardCharsets.UTF_8)).join();
        spool.enqueue("bob", "alice", "msg3".getBytes(StandardCharsets.UTF_8)).join();

        List<SpooledMessage> queue = spool.fetchForTarget("bob").join();

        assertThat(queue).hasSize(3);
        assertThat(queue.get(0).payload()).isEqualTo("msg1".getBytes(StandardCharsets.UTF_8));
        assertThat(queue.get(1).payload()).isEqualTo("msg2".getBytes(StandardCharsets.UTF_8));
        assertThat(queue.get(2).payload()).isEqualTo("msg3".getBytes(StandardCharsets.UTF_8));
        // The point of carrying senderPeerId per-row, not inferring it later: message 2 came from
        // a different sender than 1 and 3, and that must survive being queued alongside them.
        assertThat(queue.get(0).senderPeerId()).isEqualTo("alice");
        assertThat(queue.get(1).senderPeerId()).isEqualTo("carol");
        assertThat(queue.get(2).senderPeerId()).isEqualTo("alice");
    }

    @Test
    void partialDeleteLeavesUnattemptedMessagesSpooled() {
        // Simulates a peer disconnecting mid-flush after a caller (RelayRegistry, in Commit 4)
        // successfully attempted delivery of the first two messages and deleted them, but never
        // got to the third -- exactly the resilience property RelaySpool's own Javadoc calls out
        // as the reason fetch and delete are separate, per-id operations rather than one atomic
        // "drain everything" call.
        spool.enqueue("bob", "alice", "msg1".getBytes(StandardCharsets.UTF_8)).join();
        spool.enqueue("bob", "alice", "msg2".getBytes(StandardCharsets.UTF_8)).join();
        spool.enqueue("bob", "alice", "msg3".getBytes(StandardCharsets.UTF_8)).join();
        List<SpooledMessage> queue = spool.fetchForTarget("bob").join();

        spool.delete(queue.get(0).id()).join();
        spool.delete(queue.get(1).id()).join();

        List<SpooledMessage> remaining = spool.fetchForTarget("bob").join();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).payload()).isEqualTo("msg3".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void messagesForDifferentTargetsDoNotLeakIntoEachOthersFetch() {
        spool.enqueue("bob", "alice", "for-bob".getBytes(StandardCharsets.UTF_8)).join();
        spool.enqueue("dave", "alice", "for-dave".getBytes(StandardCharsets.UTF_8)).join();

        assertThat(spool.fetchForTarget("bob").join()).hasSize(1);
        assertThat(spool.fetchForTarget("dave").join()).hasSize(1);
    }

    @Test
    void fetchForTargetWithNothingSpooledReturnsEmptyNotNull() {
        assertThat(spool.fetchForTarget("nobody-has-sent-here").join()).isEmpty();
    }

    @Test
    void concurrentEnqueuesFromManyThreadsAllLandWithNoneLost() throws Exception {
        // Stands in for RelayRegistry's real situation: multiple Netty I/O worker threads, one
        // per connection, all potentially calling enqueue() at once for frames targeting the same
        // currently-offline peer. Proves the dedicated single-thread executor confinement (see
        // SqliteRelaySpool's own Javadoc) actually serializes correctly under real concurrent
        // load, not just in a single-threaded test.
        int threadCount = 20;
        int perThread = 25;
        ExecutorService hammer = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            hammer.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        spool.enqueue("concurrency-target", "sender-" + threadId,
                                ("msg-" + threadId + "-" + i).getBytes(StandardCharsets.UTF_8)).join();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        hammer.shutdown();

        assertThat(spool.fetchForTarget("concurrency-target").join()).hasSize(threadCount * perThread);
    }

    @Test
    void nullArgumentsThrowNullPointerExceptionImmediately() {
        assertThatThrownBy(() -> spool.enqueue(null, "alice", new byte[]{1}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> spool.enqueue("bob", null, new byte[]{1}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> spool.enqueue("bob", "alice", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> spool.fetchForTarget(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void spooledMessageRecordImplementsValueEqualityForPayloadBytes() {
        Instant now = Instant.now();
        SpooledMessage m1 = new SpooledMessage(1L, "alice", new byte[]{1, 2, 3}, now);
        SpooledMessage m2 = new SpooledMessage(1L, "alice", new byte[]{1, 2, 3}, now);
        SpooledMessage m3 = new SpooledMessage(2L, "alice", new byte[]{1, 2, 3}, now);

        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
        assertThat(m1).isNotEqualTo(m3);
    }
}
