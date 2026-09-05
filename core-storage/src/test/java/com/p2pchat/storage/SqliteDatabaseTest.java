package com.p2pchat.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers {@link SqliteDatabase}'s own responsibilities (file creation, permissions, migration
 * trigger) — separate from {@link SqliteStorageServiceTest}, which covers the CRUD surface built
 * on top of it.
 */
class SqliteDatabaseTest {

    // pre-m6h-hardening-plan.md finding C-2: the database file holds Double Ratchet session
    // state and plaintext message history, so it must be owner-only (0600), applied before the
    // JDBC driver ever creates it.
    @Test
    void databaseFileIsOwnerOnlyOnPosix(@TempDir Path tempDir) throws Exception {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions not supported on this filesystem");

        try (SqliteDatabase database = SqliteDatabase.openOrCreate(tempDir)) {
            Path dbFile = tempDir.resolve("p2p-chat.sqlite");
            assertThat(dbFile).exists();
            String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(dbFile));
            assertThat(permissions).isEqualTo("rw-------");
        }
    }

    // Re-opening an existing database must not fail or corrupt state just because the
    // pre-creation step now runs on every openOrCreate call — it has to be a genuine no-op
    // for a file that already exists, not just "usually harmless."
    @Test
    void reopeningExistingDatabaseStillWorks(@TempDir Path tempDir) throws SQLException {
        try (SqliteDatabase first = SqliteDatabase.openOrCreate(tempDir)) {
            assertThat(first.connection()).isNotNull();
        }
        try (SqliteDatabase second = SqliteDatabase.openOrCreate(tempDir)) {
            assertThat(second.connection().isClosed()).isFalse();
        }
    }
}
