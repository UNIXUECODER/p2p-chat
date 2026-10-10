package com.p2pchat.storage;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.ConversationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for pre-m6h-hardening-plan.md finding F-1: one JDBC {@code Connection} shared
 * across threads, with non-reentrant transactions. Each test fails on the pre-fix code for a
 * distinct reason (hardening plan R-5); see the comment on each.
 *
 * <p>"Durable" below means what an <em>independent</em> connection sees — i.e. what is actually
 * committed to the file — not what the shared connection reports about its own uncommitted state.
 *
 * <p>Concurrency is orchestrated with latches and by polling thread state, never with sleeps for
 * correctness, so the tests are deterministic on both the fixed and the pre-fix code.
 */
class SqliteConcurrencyTest {

    private Path dataDir;
    private SqliteDatabase database;
    private SqliteStorageService storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dataDir = tempDir;
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private int durableCount(String table) throws SQLException {
        try (Connection independent = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("p2p-chat.sqlite"));
             Statement statement = independent.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Contact contact(String id) {
        return new Contact(new PeerId(id), id, false, 1L);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Waits until {@code thread} has finished, or is parked waiting for a lock/monitor. */
    private static void awaitFinishedOrParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.TERMINATED || state == Thread.State.WAITING
                    || state == Thread.State.BLOCKED || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(1);
        }
    }

    // ------------------------------------------------------------------ F-1 reproductions

    /**
     * Reproduces the audit's "bystander write lost" experiment. A write that returns normally on
     * one thread must be durable even if another thread's transaction, open at the same time,
     * rolls back.
     *
     * <p>Pre-fix: the bystander's plain write lands inside the other thread's open transaction and
     * is rolled back with it — 0 contacts durable, although {@code saveContact} returned normally.
     * Fixed: the bystander waits for the transaction to finish, then commits on its own.
     */
    @Test
    void bystanderWriteSurvivesAnotherThreadsRollback() throws Exception {
        CountDownLatch inTransaction = new CountDownLatch(1);
        CountDownLatch finishTransaction = new CountDownLatch(1);

        Thread transactionThread = new Thread(() -> {
            try {
                storage.runInTransaction(() -> {
                    storage.saveConversation(new Conversation("c1", ConversationType.DIRECT, "x", 1L));
                    inTransaction.countDown();
                    await(finishTransaction);
                    throw new IllegalStateException("simulated failure inside the inbound pipeline");
                });
            } catch (IllegalStateException expected) {
                // the transaction is meant to roll back
            }
        });
        transactionThread.start();
        await(inTransaction);

        Thread bystander = new Thread(() -> storage.saveContact(contact("bystander")));
        bystander.start();
        awaitFinishedOrParked(bystander); // fixed code: parked on the lock; pre-fix: already done

        finishTransaction.countDown();
        transactionThread.join(10_000);
        bystander.join(10_000);

        assertThat(durableCount("contacts")).isEqualTo(1);      // the bystander's write survived
        assertThat(durableCount("conversations")).isEqualTo(0); // the transaction's write rolled back
    }

    /**
     * Reproduces the audit's "auto-commit stuck off" experiment at scale. After any number of
     * overlapping transactions, auto-commit must be back on and ordinary writes must be durable.
     *
     * <p>Pre-fix: overlapping {@code setAutoCommit} toggles leave auto-commit {@code false};
     * afterwards writes return success but are never committed.
     */
    @Test
    void autoCommitIsRestoredAfterManyOverlappingTransactions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                futures.add(pool.submit(() -> storage.runInTransaction(() -> {
                    try {
                        Thread.sleep(1); // widen the overlap window on the pre-fix code
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                })));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(database.connection().getAutoCommit()).isTrue();
        storage.saveContact(contact("after-the-storm"));
        assertThat(durableCount("contacts")).isEqualTo(1);
    }

    /**
     * Property test over a mixed workload: transactions (some deliberately failing) interleaved
     * with plain writes, from several threads. Whatever the interleaving, the durable row count
     * must equal exactly the number of writes that reported success and were not rolled back.
     *
     * <p>Pre-fix: plain writes are swallowed by other threads' rollbacks and by stuck auto-commit,
     * so the durable count falls short of the expected one.
     */
    @Test
    void durableRowsMatchExactlyTheWritesThatShouldHaveCommitted() throws Exception {
        int threads = 4;
        int iterations = 250;
        AtomicInteger expectedDurable = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        String id = "t" + thread + "-" + i;
                        if (i % 2 == 0) {
                            boolean failing = (i % 7 == 0);
                            try {
                                storage.runInTransaction(() -> {
                                    storage.saveContact(contact(id));
                                    if (failing) {
                                        throw new IllegalStateException("deliberate rollback");
                                    }
                                    return null;
                                });
                                expectedDurable.incrementAndGet();
                            } catch (IllegalStateException expected) {
                                // rolled back: must not be durable
                            }
                        } else {
                            storage.saveContact(contact(id));
                            expectedDurable.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(database.connection().getAutoCommit()).isTrue();
        assertThat(durableCount("contacts")).isEqualTo(expectedDurable.get());
    }

    // ------------------------------------------------------------------ re-entrancy

    /**
     * A nested {@code runInTransaction} must join the enclosing one, not commit it.
     *
     * <p>Pre-fix: the inner call commits the outer transaction early, so the row is already
     * durable while the outer body is still running.
     */
    @Test
    void nestedTransactionJoinsTheOuterOneAndDoesNotCommitEarly() throws Exception {
        storage.runInTransaction(() -> {
            storage.runInTransaction(() -> {
                storage.saveContact(contact("inner"));
                return null;
            });
            try {
                assertThat(durableCount("contacts")).isEqualTo(0); // still uncommitted
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        assertThat(durableCount("contacts")).isEqualTo(1); // committed once, by the outermost call
    }

    /**
     * If a nested body throws and the outer body swallows it, committing would persist the nested
     * body's partial work. The outermost call must refuse and roll everything back.
     *
     * <p>Pre-fix: the inner failure rolls back the shared transaction, the outer body carries on,
     * and the outer call returns normally having committed nothing — no signal that anything failed.
     */
    @Test
    void swallowedNestedFailureRollsEverythingBackAndSaysSo() throws Exception {
        assertThatThrownBy(() -> storage.runInTransaction(() -> {
            storage.saveContact(contact("outer"));
            try {
                storage.runInTransaction(() -> {
                    storage.saveContact(contact("inner"));
                    throw new IllegalArgumentException("inner failure");
                });
            } catch (IllegalArgumentException swallowed) {
                // the outer body carries on as if nothing happened
            }
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("rolled back");

        assertThat(durableCount("contacts")).isEqualTo(0);
        assertThat(database.connection().getAutoCommit()).isTrue();
    }

    /**
     * An {@link Error} inside the body must roll back, not commit.
     *
     * <p>Pre-fix: only {@code RuntimeException} was caught for rollback; on an {@code Error} the
     * {@code finally} restored auto-commit with the transaction still pending, which COMMITS it.
     */
    @Test
    void errorInsideTransactionRollsBackInsteadOfCommitting() throws Exception {
        assertThatThrownBy(() -> storage.runInTransaction(() -> {
            storage.saveContact(contact("half-done"));
            throw new AssertionError("simulated Error mid-transaction");
        })).isInstanceOf(AssertionError.class);

        assertThat(durableCount("contacts")).isEqualTo(0);
        assertThat(database.connection().getAutoCommit()).isTrue();
    }

    /** The body's own exception is rethrown unchanged — callers (e.g. SessionManager) catch specific types. */
    @Test
    void bodyExceptionIsRethrownUnchanged() {
        IllegalStateException original = new IllegalStateException("boom");
        assertThatThrownBy(() -> storage.runInTransaction(() -> {
            throw original;
        })).isSameAs(original);
    }
}
