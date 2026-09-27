package com.p2pchat.relay;

import com.p2pchat.storage.MigrationRunner;
import com.p2pchat.storage.SqliteDatabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track A-3 (pre-m6h-hardening-plan.md), Commits 2 and 3: {@link SqliteRelaySpool}'s full shape
 * — enqueue/fetch/delete (Commit 2) plus quota, max-frame-size, and TTL enforcement (Commit 3) —
 * against a real SQLite file via the same {@link SqliteDatabase} / {@link MigrationRunner}
 * machinery {@code core-storage} itself uses. Not a fake, since the whole point of this class
 * existing is the real thing (real file permissions, a real second, independent {@code
 * schema_migrations}-tracked schema, real SQLite concurrency behavior under genuinely
 * simultaneous writers).
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
        EnqueueResult result = spool.enqueue("bob", "alice", "hello bob".getBytes(StandardCharsets.UTF_8)).join();

        assertThat(result).isEqualTo(EnqueueResult.ACCEPTED);
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

    @Test
    void purgeExpiredDoesNothingWhenNothingIsPastTheCutoff() {
        spool.enqueue("bob", "alice", "fresh".getBytes(StandardCharsets.UTF_8)).join();

        int purged = spool.purgeExpired(Instant.now()).join();

        assertThat(purged).isEqualTo(0);
        assertThat(spool.fetchForTarget("bob").join()).hasSize(1);
    }

    @Test
    void purgeExpiredRemovesOnlyMessagesOlderThanTheCutoffKeepingNewerOnes() throws Exception {
        spool.enqueue("bob", "alice", "old-message".getBytes(StandardCharsets.UTF_8)).join();
        Thread.sleep(50);
        Instant midpoint = Instant.now();
        Thread.sleep(50);
        spool.enqueue("bob", "alice", "new-message".getBytes(StandardCharsets.UTF_8)).join();

        // Using the real production DEFAULT_TTL, not an artificially small one -- proves the
        // actual default works, not just that the cutoff arithmetic works in the abstract.
        int purged = spool.purgeExpired(midpoint.plus(SqliteRelaySpool.DEFAULT_TTL)).join();

        assertThat(purged).isEqualTo(1);
        List<SpooledMessage> remaining = spool.fetchForTarget("bob").join();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).payload()).isEqualTo("new-message".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void messageExceedingMaxFrameSizeIsRejectedBeforeTouchingTheDatabase(@TempDir Path isolatedDir) throws Exception {
        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        try (SqliteDatabase isolatedDb = SqliteDatabase.openOrCreate(isolatedDir, "isolated.sqlite", relayMigrations);
             SqliteRelaySpool tinySpool = new SqliteRelaySpool(isolatedDb.connection(), SqliteRelaySpool.DEFAULT_TTL,
                     SqliteRelaySpool.DEFAULT_MAX_MESSAGES_PER_PEER, SqliteRelaySpool.DEFAULT_MAX_BYTES_PER_PEER, 10)) {
            EnqueueResult result = tinySpool.enqueue("bob", "alice",
                    "this payload is well over ten bytes long".getBytes(StandardCharsets.UTF_8)).join();

            assertThat(result).isEqualTo(EnqueueResult.REJECTED_MESSAGE_TOO_LARGE);
            assertThat(tinySpool.fetchForTarget("bob").join()).isEmpty();
        }
    }

    @Test
    void messageCountQuotaRejectsOnceTheLimitIsReached(@TempDir Path isolatedDir) throws Exception {
        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        try (SqliteDatabase isolatedDb = SqliteDatabase.openOrCreate(isolatedDir, "isolated.sqlite", relayMigrations);
             SqliteRelaySpool tinySpool = new SqliteRelaySpool(isolatedDb.connection(), SqliteRelaySpool.DEFAULT_TTL,
                     3, SqliteRelaySpool.DEFAULT_MAX_BYTES_PER_PEER, SqliteRelaySpool.DEFAULT_MAX_FRAME_SIZE_BYTES)) {
            assertThat(tinySpool.enqueue("bob", "alice", "1".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.ACCEPTED);
            assertThat(tinySpool.enqueue("bob", "alice", "2".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.ACCEPTED);
            assertThat(tinySpool.enqueue("bob", "alice", "3".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.ACCEPTED);

            EnqueueResult fourth = tinySpool.enqueue("bob", "alice", "4".getBytes(StandardCharsets.UTF_8)).join();

            assertThat(fourth).isEqualTo(EnqueueResult.REJECTED_QUOTA_EXCEEDED);
            assertThat(tinySpool.fetchForTarget("bob").join()).hasSize(3);
        }
    }

    @Test
    void messageCountQuotaIsPerTargetNotGlobal(@TempDir Path isolatedDir) throws Exception {
        // A quota-exhausted target must not block a DIFFERENT target from being spooled -- per-
        // peer means per-peer, not a shared global counter across the whole relay.
        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        try (SqliteDatabase isolatedDb = SqliteDatabase.openOrCreate(isolatedDir, "isolated.sqlite", relayMigrations);
             SqliteRelaySpool tinySpool = new SqliteRelaySpool(isolatedDb.connection(), SqliteRelaySpool.DEFAULT_TTL,
                     1, SqliteRelaySpool.DEFAULT_MAX_BYTES_PER_PEER, SqliteRelaySpool.DEFAULT_MAX_FRAME_SIZE_BYTES)) {
            assertThat(tinySpool.enqueue("bob", "alice", "1".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.ACCEPTED);
            assertThat(tinySpool.enqueue("bob", "alice", "2".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.REJECTED_QUOTA_EXCEEDED);

            assertThat(tinySpool.enqueue("dave", "alice", "1".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.ACCEPTED);
        }
    }

    @Test
    void byteQuotaRejectsAMessageThatWouldPushTotalOverTheBudget(@TempDir Path isolatedDir) throws Exception {
        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        try (SqliteDatabase isolatedDb = SqliteDatabase.openOrCreate(isolatedDir, "isolated.sqlite", relayMigrations);
             SqliteRelaySpool tinySpool = new SqliteRelaySpool(isolatedDb.connection(), SqliteRelaySpool.DEFAULT_TTL,
                     SqliteRelaySpool.DEFAULT_MAX_MESSAGES_PER_PEER, 10, SqliteRelaySpool.DEFAULT_MAX_FRAME_SIZE_BYTES)) {
            // 8 bytes fits within a 10-byte budget.
            assertThat(tinySpool.enqueue("bob", "alice", "12345678".getBytes(StandardCharsets.UTF_8)).join())
                    .isEqualTo(EnqueueResult.ACCEPTED);
            // 8 + 5 = 13 > 10 -- rejected, even though the message-count limit isn't close.
            EnqueueResult second = tinySpool.enqueue("bob", "alice", "56789".getBytes(StandardCharsets.UTF_8)).join();

            assertThat(second).isEqualTo(EnqueueResult.REJECTED_QUOTA_EXCEEDED);
            assertThat(tinySpool.fetchForTarget("bob").join()).hasSize(1);
        }
    }

    @Test
    void startPeriodicPurgeActuallyRunsOnSchedule(@TempDir Path isolatedDir) throws Exception {
        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        // A deliberately tiny TTL (not the real 7-day default) so a message enqueued "now" is
        // already expired by the time the first sweep fires -- this test is about proving the
        // scheduling itself works, not re-testing purgeExpired's own cutoff logic (already
        // covered above).
        try (SqliteDatabase isolatedDb = SqliteDatabase.openOrCreate(isolatedDir, "isolated.sqlite", relayMigrations);
             SqliteRelaySpool tinySpool = new SqliteRelaySpool(isolatedDb.connection(), Duration.ofMillis(1),
                     SqliteRelaySpool.DEFAULT_MAX_MESSAGES_PER_PEER, SqliteRelaySpool.DEFAULT_MAX_BYTES_PER_PEER,
                     SqliteRelaySpool.DEFAULT_MAX_FRAME_SIZE_BYTES)) {
            tinySpool.enqueue("bob", "alice", "will expire almost immediately".getBytes(StandardCharsets.UTF_8)).join();
            tinySpool.startPeriodicPurge(Duration.ofMillis(50));

            awaitEmpty(tinySpool, "bob");

            assertThat(tinySpool.fetchForTarget("bob").join()).isEmpty();
        }
    }

    private void awaitEmpty(SqliteRelaySpool spool, String target) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!spool.fetchForTarget(target).join().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
