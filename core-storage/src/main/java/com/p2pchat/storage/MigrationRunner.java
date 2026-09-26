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
 * <p>Migration scripts are classpath resources, listed explicitly rather than discovered by
 * scanning the classpath directory. Classpath directory scanning behaves differently between
 * Gradle's exploded classes directory (used by {@code :run} / {@code JavaExec} tasks) and a
 * packaged jar (used by {@code :installDist} / {@code :distZip}), and getting that right for
 * both without being able to run and verify it here isn't worth the risk for a project that
 * doesn't yet need migrations to be discovered dynamically. Add new scripts to the end of the
 * list as they're written; per §9, never hand-edit a script once it has shipped — write a new
 * one instead.
 *
 * <p><b>Track A-3 (pre-m6h-hardening-plan.md):</b> the resource root and migration list are now
 * constructor parameters, not hardcoded static fields — {@code relay-server}'s own spool schema
 * needs this same version-tracked-apply-once machinery against a completely separate database
 * file and a completely separate migration set, and duplicating this class's ~100 lines of
 * transaction/rollback/bookkeeping logic to get it would be exactly the kind of drift this
 * project avoids elsewhere. The no-arg constructor is unchanged in behavior — it delegates to
 * this class's own original hardcoded root and list — so every existing caller ({@link
 * SqliteDatabase}, this class's own tests) needed no changes at all.
 */
public final class MigrationRunner {

    private static final List<String> DEFAULT_MIGRATIONS = List.of(
            "V001__init.sql",
            "V002__signal_store.sql",
            "V003__kyber_base_key_replay.sql",
            "V004__peer_routes.sql"
    );

    private static final String DEFAULT_RESOURCE_ROOT = "/db/migration/";

    private final String resourceRoot;
    private final List<String> migrations;

    /**
     * core-storage's own migration set, unchanged from before this class took constructor
     * parameters — equivalent to {@code new MigrationRunner(DEFAULT_RESOURCE_ROOT,
     * DEFAULT_MIGRATIONS)}.
     */
    public MigrationRunner() {
        this(DEFAULT_RESOURCE_ROOT, DEFAULT_MIGRATIONS);
    }

    /**
     * Track A-3: lets a caller outside core-storage (namely {@code relay-server}'s own spool)
     * apply its own, entirely separate versioned migration set against its own database file,
     * with its own independent {@code schema_migrations} bookkeeping table — reusing this
     * class's transaction/rollback/apply-once logic rather than re-implementing it.
     *
     * @param resourceRoot classpath directory the migration scripts live under, trailing slash
     *                     included (e.g. {@code "/db/relay-migration/"}) — kept as a full path
     *                     rather than a bare directory name so it stays a direct, unambiguous
     *                     match for {@link Class#getResourceAsStream(String)}'s own convention
     * @param migrations   migration script filenames in application order, matching whatever
     *                     naming convention the caller has chosen for its own scripts — this
     *                     class only cares about applying them in list order and recording how
     *                     many have been applied, never about the filenames' own content
     */
    public MigrationRunner(String resourceRoot, List<String> migrations) {
        this.resourceRoot = resourceRoot;
        this.migrations = List.copyOf(migrations);
    }

    /**
     * Applies every migration in this instance's migration list that hasn't already been
     * recorded in {@code schema_migrations}, in order, each in its own transaction.
     */
    public void migrate(Connection connection) throws SQLException {
        ensureMigrationsTable(connection);
        int currentVersion = currentVersion(connection);

        for (int i = 0; i < migrations.size(); i++) {
            int scriptVersion = i + 1;
            if (scriptVersion <= currentVersion) {
                continue;
            }
            applyMigration(connection, scriptVersion, migrations.get(i));
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
        String path = resourceRoot + resourceName;
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
