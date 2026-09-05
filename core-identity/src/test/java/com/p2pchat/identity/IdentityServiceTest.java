package com.p2pchat.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    // pre-m6h-hardening-plan.md finding C-2: identity.key must be owner-only (0600), applied at
    // creation, not chmod'd afterward. Skips on filesystems without POSIX permission support
    // (Windows) rather than failing there, since that's a documented, accepted gap — see
    // JavaIdentityService.writeOwnerOnly's Javadoc — not a regression to catch on those platforms.
    @Test
    void identityKeyFileIsOwnerOnlyOnPosix(@TempDir Path tempDir) throws Exception {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions not supported on this filesystem");

        new JavaIdentityService(tempDir).createIdentity("Alice");

        Path keyFile = tempDir.resolve("identity.key");
        assertThat(keyFile).exists();
        String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile));
        assertThat(permissions).isEqualTo("rw-------");

        // identity.pub is meant to be shared (it's the public key), so it's deliberately NOT
        // locked down the same way — only identity.key (the private key) is sensitive.
        Path pubFile = tempDir.resolve("identity.pub");
        assertThat(pubFile).exists();
    }
}
