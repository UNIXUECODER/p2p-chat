package com.p2pchat.daemon.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RpcAuthTokenTest {

    @Test
    void generatesA64CharacterHexToken(@TempDir Path tempDir) throws Exception {
        String token = RpcAuthToken.generateAndPersist(tempDir.resolve("rpc-token"));

        assertThat(token).hasSize(64); // 32 bytes, hex-encoded
        assertThat(token).matches("[0-9a-f]{64}");
    }

    @Test
    void persistedFileContentsMatchTheReturnedToken(@TempDir Path tempDir) throws Exception {
        Path tokenFile = tempDir.resolve("rpc-token");
        String token = RpcAuthToken.generateAndPersist(tokenFile);

        assertThat(Files.readString(tokenFile)).isEqualTo(token);
    }

    @Test
    void eachCallGeneratesADifferentToken(@TempDir Path tempDir) throws Exception {
        // pre-m6h-hardening-plan.md finding C-1: regenerated fresh per daemon start, deliberately
        // not persisted long-term, specifically so a token leaked from one session doesn't grant
        // access to a later one.
        String first = RpcAuthToken.generateAndPersist(tempDir.resolve("token-a"));
        String second = RpcAuthToken.generateAndPersist(tempDir.resolve("token-b"));

        assertThat(first).isNotEqualTo(second);
    }

    // pre-m6h-hardening-plan.md finding C-2's same requirement, applied here: the token file is
    // exactly as sensitive as an identity key -- anyone who can read it can drive the JSON-RPC API.
    @Test
    void tokenFileIsOwnerOnlyOnPosix(@TempDir Path tempDir) throws Exception {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions not supported on this filesystem");

        Path tokenFile = tempDir.resolve("rpc-token");
        RpcAuthToken.generateAndPersist(tokenFile);

        String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile));
        assertThat(permissions).isEqualTo("rw-------");
    }
}
