package com.p2pchat.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Opens (creating and migrating if needed) the SQLite database for a data directory, matching
 * the same "one file per data dir" convention core-identity and core-crypto's key vaults
 * already use. A single, long-lived {@link Connection} is held and reused for the process's
 * lifetime, and nothing in this project yet needs a connection pool.
 *
 * <p><b>Threading (pre-m6h-hardening-plan.md F-1).</b> A JDBC {@code Connection} is not a safe
 * place for several threads to interleave statements and transactions: a second thread's plain
 * write lands inside a first thread's open transaction and is rolled back with it, and two
 * overlapping {@code setAutoCommit} toggles can leave auto-commit stuck off. So this class owns
 * one re-entrant lock covering every use of the connection. Callers take a {@link #lease()} for
 * statement execution and use {@link #inTransaction} for transactions, which holds the lock from
 * the first statement to the commit.
 *
 * <p><b>Lock order</b> (hardening plan R-1), outermost first: the per-peer crypto lock (F-2, not
 * yet implemented), then {@code SynchronizedSignalProtocolStore}'s monitor, then this lock. Never acquire them in the
 * other direction, and never call the network, an event listener, or any other blocking
 * operation while holding a lease or inside a transaction body.
 */
public final class SqliteDatabase implements AutoCloseable {

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    /** Nesting depth of {@link #inTransaction}; only ever touched by the thread holding {@link #lock}. */
    private int transactionDepth;

    /** Set when a nested transaction body failed, so the outermost one refuses to commit. Guarded by {@link #lock}. */
    private boolean rollbackOnly;

    private SqliteDatabase(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens {@code <dataDir>/p2p-chat.sqlite}, creating the directory and file if they don't
     * exist yet, then applies any pending migrations via {@link MigrationRunner}.
     */
    public static SqliteDatabase openOrCreate(Path dataDir) {
        return openOrCreate(dataDir, "p2p-chat.sqlite", new MigrationRunner());
    }

    /**
     * Track A-3 (pre-m6h-hardening-plan.md): lets a caller outside this class's own default
     * (namely {@code relay-server}'s own spool) open a database under a different filename in
     * the same data directory, migrated by its own independent {@link MigrationRunner} — reusing
     * this class's directory creation, owner-only permission pre-creation (pre-m6h-hardening-
     * plan.md finding C-2), and {@code PRAGMA foreign_keys} setup rather than re-implementing
     * them. The 1-arg overload above is unchanged behavior, equivalent to calling this one with
     * {@code "p2p-chat.sqlite"} and {@code new MigrationRunner()}.
     *
     * @param fileName        database filename within {@code dataDir}, e.g. {@code
     *                        "relay-spool.sqlite"} — a distinct name specifically so a caller
     *                        that also uses the 1-arg overload's default filename in the same
     *                        directory (unlikely, but not this method's business to prevent)
     *                        doesn't collide with it
     * @param migrationRunner the caller's own migration set, applied exactly as {@link
     *                        MigrationRunner#migrate} already does for the default case
     */
    public static SqliteDatabase openOrCreate(Path dataDir, String fileName, MigrationRunner migrationRunner) {
        try {
            Files.createDirectories(dataDir);
            Path dbFile = dataDir.resolve(fileName);
            preCreateOwnerOnly(dbFile);
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            migrationRunner.migrate(connection);
            return new SqliteDatabase(connection);
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to open/migrate SQLite database in " + dataDir, e);
        }
    }

    /**
     * If {@code dbFile} doesn't exist yet, creates it empty with owner-only permissions
     * ({@code rw-------} / 0600) before the SQLite JDBC driver ever touches it, so there's no
     * window where the database — which holds Double Ratchet session state and plaintext message
     * history — exists on disk at default/umask permissions. See pre-m6h-hardening-plan.md
     * finding C-2. The driver itself doesn't expose a way to pass file attributes into its own
     * file creation, which is why this pre-creates an empty file rather than opening a connection
     * and chmod-ing afterward (the same race window C-2 flags for the key-vault writes in
     * core-identity/core-crypto, avoided the same way: set permissions atomically at the moment
     * the file is actually created, not after).
     *
     * <p>Deliberately a no-op if the file already exists — an existing database created before
     * this fix keeps whatever permissions it already had; this doesn't retroactively tighten it.
     * Falls back to doing nothing (leaving driver-default creation) on filesystems without POSIX
     * permission support (Windows) — documented gap, not a crash.
     *
     * <p><b>Known residual gap, not fixed here:</b> SQLite's WAL/rollback-journal sidecar files
     * ({@code p2p-chat.sqlite-wal}, {@code -shm}, {@code -journal}) are created by the driver
     * itself on demand, not through this method, so they inherit the process's default umask
     * rather than an owner-only mode. There's no JDK-level hook to fix this per-file the way the
     * main database file is fixed above. The real fix is a process-wide one: whatever launches
     * the daemon (M6h's {@code DaemonMain}, or its shell wrapper) should call {@code umask 077}
     * before the JVM starts, which covers every file the process creates — these sidecars
     * included — without needing a per-file trick at all. Tracked as an M6h startup-sequence
     * item, not a core-storage one.
     */
    private static void preCreateOwnerOnly(Path dbFile) throws IOException {
        if (Files.exists(dbFile) || !dbFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(ownerOnly);
        Files.createFile(dbFile, attr);
    }

    /**
     * The live connection backing this database. <b>Not safe to use from several threads on its
     * own</b> — take a {@link #lease()} for any statement execution and use
     * {@link #inTransaction} for a transaction. Retained for single-threaded setup code and tests.
     * Callers must not close it directly — use {@link #close()}.
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Acquires this database's lock and returns a handle to the connection that releases it on
     * {@link Lease#close()}. Use in a try-with-resources <em>ahead of</em> the statement, so the
     * lock outlives the statement's execution and result-set iteration:
     *
     * <pre>{@code
     * try (SqliteDatabase.Lease lease = database.lease();
     *      PreparedStatement statement = lease.connection().prepareStatement(sql)) {
     *     ...
     * }
     * }</pre>
     *
     * Re-entrant: a thread that already holds the lock (including from inside {@link
     * #inTransaction}) may take further leases. A thread that does not hold it blocks until the
     * current holder — including an open transaction — finishes, which is exactly what keeps
     * another thread's write out of that transaction.
     */
    public Lease lease() {
        lock.lock();
        return new Lease();
    }

    /**
     * Runs {@code work} in a transaction, holding the database lock from the first statement to
     * the commit (or rollback). Re-entrant: a call made while this thread is already inside a
     * transaction <em>joins</em> it — it neither commits early nor starts a second transaction. If
     * a joined (nested) body throws, the outermost transaction is marked rollback-only: even if
     * the outer body swallows the exception, the outermost call rolls everything back and throws
     * {@link IllegalStateException} instead of committing partial work.
     *
     * <p>Auto-commit is always restored to {@code true} afterwards, whatever happened. {@link
     * Error}s roll back too (previously only {@link RuntimeException} did, and restoring
     * auto-commit with a transaction still pending would have committed it).
     *
     * @throws RuntimeException the body's own exception, unchanged; or {@code "Transaction failed"}
     *                          wrapping an {@link SQLException} from begin/commit/rollback
     */
    public <T> T inTransaction(Supplier<T> work) {
        lock.lock();
        try {
            if (transactionDepth > 0) {
                transactionDepth++;
                try {
                    return work.get();
                } catch (RuntimeException | Error e) {
                    rollbackOnly = true;
                    throw e;
                } finally {
                    transactionDepth--;
                }
            }
            return runOutermostTransaction(work);
        } finally {
            lock.unlock();
        }
    }

    private <T> T runOutermostTransaction(Supplier<T> work) {
        T result = null;
        Throwable failure = null;
        try {
            connection.setAutoCommit(false);
            transactionDepth = 1;
            rollbackOnly = false;
            result = work.get();
            if (rollbackOnly) {
                throw new IllegalStateException(
                        "Transaction rolled back: a nested transaction failed and its exception was swallowed");
            }
            connection.commit();
        } catch (Throwable t) {
            failure = t;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                t.addSuppressed(rollbackFailure);
            }
        } finally {
            transactionDepth = 0;
            rollbackOnly = false;
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                failure = new RuntimeException("Failed to restore auto-commit after transaction", e);
            }
        }
        if (failure == null) {
            return result;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        throw new RuntimeException("Transaction failed", failure);
    }

    @Override
    public void close() throws SQLException {
        lock.lock();
        try {
            connection.close();
        } finally {
            lock.unlock();
        }
    }

    /** A held lock on this database plus access to its connection; see {@link #lease()}. */
    public final class Lease implements AutoCloseable {
        private boolean open = true;

        private Lease() {
        }

        /** The connection, valid until this lease is closed. */
        public Connection connection() {
            if (!open) {
                throw new IllegalStateException("Lease already released");
            }
            return connection;
        }

        /** Releases the lock. Idempotent; must be called by the thread that took the lease. */
        @Override
        public void close() {
            if (open) {
                open = false;
                lock.unlock();
            }
        }
    }
}
