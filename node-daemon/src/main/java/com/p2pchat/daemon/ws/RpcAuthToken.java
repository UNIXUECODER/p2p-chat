package com.p2pchat.daemon.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * pre-m6h-hardening-plan.md finding C-1, the third of its three required changes: a random token,
 * generated fresh at daemon start, required by {@link HandshakeAuthHandler} on every WebSocket
 * connection attempt. This is what stops *other local processes* — any npm postinstall script,
 * any browser extension with native messaging — from driving the JSON-RPC API even from
 * localhost, which loopback-binding and Origin-checking alone don't address (a same-machine
 * process is still "local" and typically has no Origin header at all).
 *
 * <p>Deliberately regenerated every daemon start rather than persisted long-term: a fresh token
 * per run means a token leaked from one session (a crash log, a careless copy-paste) doesn't
 * grant access to a later one. The token file's location is intentionally NOT hardcoded here —
 * see {@code DaemonWebSocketServer}'s own Javadoc on why this takes a {@code Path} rather than
 * assuming {@code ~/.p2p-chat}, which is what the audit's own wording literally suggests but
 * isn't actually this project's established convention.
 */
public final class RpcAuthToken {

    private static final int TOKEN_BYTES = 32; // 256 bits -- 64 hex characters once encoded

    private RpcAuthToken() {
    }

    /**
     * Generates a fresh random token, writes it to {@code tokenFile} with owner-only permissions
     * (0600), and returns it. Hex-encoded rather than base64: this project already uses hex for
     * comparable values (file hashes), and unlike base64 it needs no thought at all about
     * URL-encoding when placed in a WebSocket URI's query string, since a hex string is always
     * URL-safe as-is.
     */
    public static String generateAndPersist(Path tokenFile) throws IOException {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        String token = HexFormat.of().formatHex(randomBytes);
        writeOwnerOnly(tokenFile, token.getBytes(StandardCharsets.UTF_8));
        return token;
    }

    // Third copy of this exact helper -- see JavaIdentityService/SignalIdentityVault/
    // SqliteDatabase's own copies (pre-m6h-hardening-plan.md finding C-2) for why it's duplicated
    // rather than shared: this module doesn't depend on either of those, and the alternative is
    // manufacturing a coupling point for five lines of code.
    private static void writeOwnerOnly(Path path, byte[] bytes) throws IOException {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.write(path, bytes); // documented gap on non-POSIX filesystems, e.g. Windows
            return;
        }
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(ownerOnly);
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                attr)) {
            channel.write(ByteBuffer.wrap(bytes));
        }
    }
}
