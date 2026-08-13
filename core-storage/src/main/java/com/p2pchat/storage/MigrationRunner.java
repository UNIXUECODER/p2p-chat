package com.p2pchat.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies versioned SQL scripts to a database, tracking which versions have already been
 * applied in a bookkeeping table ({@code schema_migrations}) so re-running on an
 * already-migrated database is a no-op. Matches the naming convention
 * docs/architecture-spec.md §9 already specifies ("V001__init.sql, V002__...sql").
 *
 * <p>Migration scripts are classpath resources under {@code db/migration/}, listed explicitly
 * in {@link #MIGRATIONS} rather than discovered by scanning the classpath directory. Classpath
 * directory scanning behaves differently between Gradle's exploded classes directory (used by
 * {@code :run} / {@code JavaExec} tasks) and a packaged jar (used by {@code :installDist} /
 * {@code :distZip}), and getting that right for both without being able to run and verify it
 * here isn't worth the risk for a project that doesn't yet need migrations to be discovered
 * dynamically. Add new scripts to the end of the list as they're written; per §9, never
 * hand-edit a script once it has shipped — write a new one instead.
 */
public final class MigrationRunner {

    private static final List<String> MIGRATIONS = List.of(
            "V001__init.sql"
    );

    private static final String RESOURCE_ROOT = "/db/migration/";

    /**
     * Applies every migration in {@link #MIGRATIONS} that hasn't already been recorded in
     * {@code schema_migrations}, in order, each in its own transaction.
     */
    public void migrate(Connection connection) throws SQLException {
        ensureMigrationsTable(connection);
        int currentVersion = currentVersion(connection);

        for (int i = 0; i < MIGRATIONS.size(); i++) {
            int scriptVersion = i + 1;
            if (scriptVersion <= currentVersion) {
                continue;
            }
            applyMigration(connection, scriptVersion, MIGRATIONS.get(i));
        }
    }

    private void ensureMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                            "version INTEGER PRIMARY KEY, " +
                            "applied_at INTEGER NOT NULL)");
        }
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(version), 0) AS v FROM schema_migrations")) {
            return resultSet.next() ? resultSet.getInt("v") : 0;
        }
    }

    private void applyMigration(Connection connection, int version, String resourceName) throws SQLException {
        String sql = readResource(resourceName);
        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // Every migration script this project writes is pure DDL with no semicolons
                // inside string literals or comments, so a plain split on ";" is sufficient —
                // this is not a general-purpose SQL statement splitter.
                for (String individualStatement : sql.split(";")) {
                    String trimmed = individualStatement.trim();
                    if (!trimmed.isEmpty()) {
                        statement.execute(trimmed);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)")) {
                insert.setInt(1, version);
                insert.setLong(2, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new SQLException("Failed to apply migration " + resourceName + " (version " + version + ")", e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private String readResource(String resourceName) {
        String path = RESOURCE_ROOT + resourceName;
        try (InputStream in = MigrationRunner.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Migration resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read migration resource: " + path, e);
        }
    }
}
