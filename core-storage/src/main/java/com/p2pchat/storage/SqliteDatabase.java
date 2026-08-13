package com.p2pchat.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
        try {
            Files.createDirectories(dataDir);
            Path dbFile = dataDir.resolve("p2p-chat.sqlite");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            new MigrationRunner().migrate(connection);
            return new SqliteDatabase(connection);
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to open/migrate SQLite database in " + dataDir, e);
        }
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
