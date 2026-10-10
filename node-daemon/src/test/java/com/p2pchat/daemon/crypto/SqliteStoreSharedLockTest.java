package com.p2pchat.daemon.crypto;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.model.Contact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.util.KeyHelper;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for pre-m6h-hardening-plan.md finding F-1 as it affects the Signal store:
 * {@link SqliteSignalProtocolStore} and {@link SqliteStorageService} share one JDBC connection,
 * so a session write must neither land inside another thread's storage transaction (and be
 * rolled back or committed with it) nor race the transaction's auto-commit toggling. The
 * Signal-store side matters most: lost ratchet state or an undeleted one-time pre-key undermine
 * the forward-secrecy claim.
 *
 * <p>"Durable" means what an independent connection sees. Fails on the pre-fix code (R-5).
 */
class SqliteStoreSharedLockTest {

    private Path dataDir;
    private SqliteDatabase database;
    private SqliteStorageService storage;
    private SqliteSignalProtocolStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dataDir = tempDir;
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
        store = new SqliteSignalProtocolStore(
                database, IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    private int durableCount(String table) throws SQLException {
        try (Connection independent = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("p2p-chat.sqlite"));
             Statement statement = independent.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
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

    private static void awaitFinishedOrParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state != Thread.State.RUNNABLE && state != Thread.State.NEW) {
                return;
            }
            Thread.sleep(1);
        }
    }

    /**
     * A session write made while another thread's storage transaction is open must wait for it,
     * then be durable on its own — even though that transaction rolls back.
     *
     * <p>Pre-fix: the session write lands inside the open transaction and is rolled back with it,
     * so the ratchet state the caller believes it stored is gone.
     */
    @Test
    void sessionWriteWaitsForAnOpenStorageTransactionAndSurvivesItsRollback() throws Exception {
        CountDownLatch inTransaction = new CountDownLatch(1);
        CountDownLatch finishTransaction = new CountDownLatch(1);

        Thread transactionThread = new Thread(() -> {
            try {
                storage.runInTransaction(() -> {
                    storage.saveContact(new Contact(new PeerId("tx-contact"), "Tx", false, 1L));
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

        Thread sessionWriter = new Thread(() ->
                store.storeSession(new SignalProtocolAddress("peer", 1), new SessionRecord()));
        sessionWriter.start();
        awaitFinishedOrParked(sessionWriter); // fixed code: parked on the lock; pre-fix: already done

        finishTransaction.countDown();
        transactionThread.join(10_000);
        sessionWriter.join(10_000);

        assertThat(durableCount("signal_sessions")).isEqualTo(1); // the ratchet write survived
        assertThat(durableCount("contacts")).isEqualTo(0);        // the transaction's write rolled back
    }

    /**
     * Session writes interleaved with storage transactions (some rolled back) from several
     * threads: every session write must be durable and auto-commit must end up on.
     *
     * <p>Pre-fix: session writes are swallowed by other threads' rollbacks or by a stuck
     * auto-commit-off connection, so the durable count falls short.
     */
    @Test
    void sessionWritesInterleavedWithTransactionsAreAllDurable() throws Exception {
        int threads = 4;
        int sessionsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < sessionsPerThread; i++) {
                        store.storeSession(new SignalProtocolAddress("peer-" + thread + "-" + i, 1), new SessionRecord());
                    }
                }));
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < sessionsPerThread; i++) {
                        final int n = i;
                        try {
                            storage.runInTransaction(() -> {
                                storage.saveContact(new Contact(new PeerId("c-" + thread + "-" + n), "C", false, 1L));
                                if (n % 5 == 0) {
                                    throw new IllegalStateException("deliberate rollback");
                                }
                                return null;
                            });
                        } catch (IllegalStateException expected) {
                            // rolled back
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
        assertThat(durableCount("signal_sessions")).isEqualTo(threads * sessionsPerThread);
        // 4 threads x 100 iterations, every 5th rolled back => 4 x 80 contacts committed.
        assertThat(durableCount("contacts")).isEqualTo(threads * (sessionsPerThread - sessionsPerThread / 5));
    }
}
