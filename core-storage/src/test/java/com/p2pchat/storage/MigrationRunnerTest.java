package com.p2pchat.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A-3 (pre-m6h-hardening-plan.md): {@link MigrationRunner} took a resource root and
 * migration list as constructor parameters instead of hardcoded static fields, specifically so
 * {@code relay-server}'s own spool schema can reuse this class's transaction/rollback/apply-once
 * logic against its own, entirely separate database file. Two things worth proving here, not
 * just reading: the no-arg constructor is genuinely unchanged behavior (zero regression for
 * every existing caller), and the parameterized constructor genuinely works end to end against a
 * real classpath resource and a real SQLite connection, not just compiles.
 */
class MigrationRunnerTest {

    @Test
    void noArgConstructorStillAppliesCoreStoragesOwnFourMigrations(@TempDir Path tempDir) throws Exception {
        // Goes through the real SqliteDatabase.openOrCreate entry point every existing caller
        // (SqliteStorageService, every node-daemon Main) actually uses — not a hand-built
        // Connection — so this is a genuine regression test for the refactor, not just a check
        // that the no-arg constructor happens to exist.
        try (SqliteDatabase database = SqliteDatabase.openOrCreate(tempDir)) {
            try (Statement statement = database.connection().createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT MAX(version) AS v FROM schema_migrations")) {
                resultSet.next();
                assertThat(resultSet.getInt("v")).isEqualTo(4);
            }
        }
    }

    @Test
    void parameterizedConstructorAppliesACompletelySeparateMigrationSet(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("standalone-test.sqlite").toString();
        MigrationRunner runner = new MigrationRunner("/db/test-migration/", List.of("V001__widgets.sql"));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            runner.migrate(connection);

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO widgets (name) VALUES ('gadget')");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT name FROM widgets")) {
                resultSet.next();
                assertThat(resultSet.getString("name")).isEqualTo("gadget");
            }

            // Re-running migrate() on an already-migrated connection must stay a no-op, the same
            // guarantee the no-arg path already gives — not a new promise this constructor needs
            // to prove separately, but worth confirming it isn't accidentally lost.
            runner.migrate(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT MAX(version) AS v FROM schema_migrations")) {
                resultSet.next();
                assertThat(resultSet.getInt("v")).isEqualTo(1);
            }
        }
    }

    @Test
    void separateMigrationSetsDoNotInterfere(@TempDir Path tempDir) throws Exception {
        // core-storage's own migrations and a completely unrelated custom set, run against two
        // separate database files, must not share state or schema in any way -- the whole point
        // of this refactor is that relay-server's spool is its own isolated thing.
        try (SqliteDatabase coreStorageDb = SqliteDatabase.openOrCreate(tempDir.resolve("core"))) {
            String relayDbPath = tempDir.resolve("relay.sqlite").toString();
            try (Connection relayConnection = DriverManager.getConnection("jdbc:sqlite:" + relayDbPath)) {
                new MigrationRunner("/db/test-migration/", List.of("V001__widgets.sql")).migrate(relayConnection);

                try (Statement statement = coreStorageDb.connection().createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT MAX(version) AS v FROM schema_migrations")) {
                    resultSet.next();
                    assertThat(resultSet.getInt("v")).isEqualTo(4);
                }
                try (Statement statement = relayConnection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT MAX(version) AS v FROM schema_migrations")) {
                    resultSet.next();
                    assertThat(resultSet.getInt("v")).isEqualTo(1);
                }
            }
        }
    }
}
