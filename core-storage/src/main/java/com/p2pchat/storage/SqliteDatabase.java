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

/**
 * Opens (creating and migrating if needed) the SQLite database for a data directory, matching
 * the same "one file per data dir" convention core-identity and core-crypto's key vaults
 * already use. A single, long-lived {@link Connection} is held and reused for the process's
 * lifetime — SQLite itself serializes writers internally, and nothing in this project yet needs
 * a connection pool.
 */
public final class SqliteDatabase implements AutoCloseable {

    private final Connection connection;

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

    /** The live connection backing this database. Callers must not close it directly — use {@link #close()}. */
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
