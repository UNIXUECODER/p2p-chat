package com.p2pchat.crypto;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * Persists the Signal identity (IdentityKeyPair + registration ID) to disk,
 * parallel to how core-identity persists the network identity — but entirely
 * within libsignal's own types (IdentityKeyPair.serialize() / the
 * IdentityKeyPair(byte[]) constructor, both confirmed against the real
 * v0.94.0 source). Unlike M1.5's network-identity binding, there's no
 * cross-library byte-format conversion involved here at all.
 */
public final class SignalIdentityVault {

    private SignalIdentityVault() {
    }

    public static SignalIdentity loadOrCreate(Path dataDir) {
        try {
            Path keyFile = dataDir.resolve("signal-identity.key");
            Path regFile = dataDir.resolve("signal-identity.reg");

            if (Files.exists(keyFile) && Files.exists(regFile)) {
                IdentityKeyPair keyPair = new IdentityKeyPair(Files.readAllBytes(keyFile));
                int registrationId = ByteBuffer.wrap(Files.readAllBytes(regFile)).getInt();
                return new SignalIdentity(keyPair, registrationId);
            }

            Files.createDirectories(dataDir);
            IdentityKeyPair keyPair = IdentityKeyPair.generate();
            int registrationId = KeyHelper.generateRegistrationId(false);

            writeOwnerOnly(keyFile, keyPair.serialize());
            writeOwnerOnly(regFile, ByteBuffer.allocate(4).putInt(registrationId).array());

            return new SignalIdentity(keyPair, registrationId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create Signal identity in " + dataDir, e);
        }
    }

    /**
     * Writes {@code bytes} to {@code path} with owner-only permissions ({@code rw-------} / 0600)
     * applied at file-creation time rather than via a {@code chmod} afterward, which leaves a
     * race window where the file briefly exists at default/umask permissions. See
     * pre-m6h-hardening-plan.md finding C-2 and core-identity's {@code JavaIdentityService},
     * which has the identical helper — duplicated rather than pulled into a shared module, since
     * core-crypto and core-identity don't otherwise depend on each other or on anything that
     * could host a five-line file-permission utility without becoming a new, unwarranted
     * coupling point (see core-model's own build.gradle.kts comment on exactly this trade-off).
     *
     * <p>Falls back to a plain write and documents the gap on filesystems without POSIX
     * permission support (Windows, most notably) — an ACL-based real fix is out of scope here,
     * same interim-measure framing as the identity module's copy of this helper.
     */
    private static void writeOwnerOnly(Path path, byte[] bytes) throws IOException {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.write(path, bytes); // documented gap on non-POSIX filesystems — see Javadoc above
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
