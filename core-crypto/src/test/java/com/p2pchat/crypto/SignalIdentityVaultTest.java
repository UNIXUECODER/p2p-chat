package com.p2pchat.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SignalIdentityVaultTest {

    @Test
    void loadOrCreateGeneratesAndPersistsIdentity(@TempDir Path tempDir) {
        SignalIdentity identity1 = SignalIdentityVault.loadOrCreate(tempDir);

        assertThat(identity1).isNotNull();
        assertThat(identity1.keyPair()).isNotNull();
        assertThat(identity1.registrationId()).isNotZero();

        assertThat(tempDir.resolve("signal-identity.key")).exists();
        assertThat(tempDir.resolve("signal-identity.reg")).exists();

        // Loading again from the same directory must load the exact same identity
        SignalIdentity identity2 = SignalIdentityVault.loadOrCreate(tempDir);

        assertThat(identity2.registrationId()).isEqualTo(identity1.registrationId());
        assertThat(identity2.keyPair().serialize()).isEqualTo(identity1.keyPair().serialize());
    }

    // pre-m6h-hardening-plan.md finding C-2: both the Signal identity keypair and the
    // registration-ID file must be owner-only (0600), applied at creation.
    @Test
    void signalIdentityFilesAreOwnerOnlyOnPosix(@TempDir Path tempDir) throws Exception {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions not supported on this filesystem");

        SignalIdentityVault.loadOrCreate(tempDir);

        for (String fileName : new String[] {"signal-identity.key", "signal-identity.reg"}) {
            Path file = tempDir.resolve(fileName);
            assertThat(file).exists();
            String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
            assertThat(permissions).as("permissions of %s", fileName).isEqualTo("rw-------");
        }
    }
}
