package com.p2pchat.storage;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Semantics of {@link SqliteDatabase#lease()} (pre-m6h-hardening-plan.md F-1). */
class SqliteDatabaseLeaseTest {

    private SqliteDatabase database;
    private SqliteStorageService storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    private static Thread.State awaitParkedOrDone(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state != Thread.State.RUNNABLE && state != Thread.State.NEW) {
                return state;
            }
            Thread.sleep(1);
        }
        return thread.getState();
    }

    @Test
    void aHeldLeaseKeepsOtherThreadsOutUntilItIsClosed() throws Exception {
        Thread other = new Thread(() ->
                storage.saveContact(new Contact(new PeerId("other-thread"), "Other", false, 1L)));

        try (SqliteDatabase.Lease lease = database.lease()) {
            assertThat(lease.connection()).isNotNull();
            other.start();
            // Parked on the database lock, not running and not finished.
            assertThat(awaitParkedOrDone(other)).isNotEqualTo(Thread.State.TERMINATED);
            assertThat(other.isAlive()).isTrue();
        }

        other.join(10_000);
        assertThat(other.isAlive()).isFalse();
        assertThat(storage.listContacts()).hasSize(1);
    }

    @Test
    void leasesAreReentrantOnTheSameThread() {
        try (SqliteDatabase.Lease outer = database.lease();
             SqliteDatabase.Lease inner = database.lease()) {
            assertThat(inner.connection()).isSameAs(outer.connection());
            // Storage calls from the thread that holds a lease must not deadlock itself.
            storage.saveContact(new Contact(new PeerId("same-thread"), "Same", false, 1L));
        }
        assertThat(storage.listContacts()).hasSize(1);
    }

    @Test
    void closeIsIdempotentAndTheConnectionIsUnusableAfterwards() {
        SqliteDatabase.Lease lease = database.lease();
        lease.close();
        lease.close(); // must not unlock twice (would throw IllegalMonitorStateException)
        assertThatThrownBy(lease::connection).isInstanceOf(IllegalStateException.class);

        // The lock really was released: this thread can take it again and others can proceed.
        try (SqliteDatabase.Lease again = database.lease()) {
            assertThat(again.connection()).isNotNull();
        }
    }
}
