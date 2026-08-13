package com.p2pchat.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
