package com.p2pchat.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityServiceTest {

    @Test
    void createAndLoadIdentity(@TempDir Path tempDir) throws Exception {
        IdentityService service = new JavaIdentityService(tempDir);
        assertThat(service.hasIdentity()).isFalse();

        Identity created = service.createIdentity("Alice");
        assertThat(created).isNotNull();
        assertThat(created.displayName()).isEqualTo("Alice");
        assertThat(created.peerId()).isNotNull().isNotEmpty();
        assertThat(created.publicKey()).isNotNull();

        assertThat(service.hasIdentity()).isTrue();

        Identity loaded = service.loadIdentity();
        assertThat(loaded.peerId()).isEqualTo(created.peerId());
        assertThat(loaded.displayName()).isEqualTo("Alice");
        assertThat(loaded.publicKey()).isEqualTo(created.publicKey());

        byte[] seed = service.rawPrivateKeySeed();
        assertThat(seed).isNotNull().hasSize(32);
    }

    @Test
    void loadIdentityThrowsWhenMissing(@TempDir Path tempDir) {
        IdentityService service = new JavaIdentityService(tempDir);
        assertThatThrownBy(service::loadIdentity)
                .isInstanceOf(IdentityNotFoundException.class);
        assertThatThrownBy(service::rawPrivateKeySeed)
                .isInstanceOf(IllegalStateException.class);
    }
}
