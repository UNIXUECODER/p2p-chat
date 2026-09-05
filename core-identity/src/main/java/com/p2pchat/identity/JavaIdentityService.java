package com.p2pchat.identity;

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
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.EnumSet;
import java.util.Set;

public class JavaIdentityService implements IdentityService {

    private final Path baseDir;

    public JavaIdentityService(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public boolean hasIdentity() {
        return Files.exists(baseDir.resolve("identity.pub"));
    }

    @Override
    public Identity createIdentity(String displayName) {
        try {
            Files.createDirectories(baseDir);

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = kpg.generateKeyPair();

            byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
            byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();

            Files.write(baseDir.resolve("identity.pub"), publicKeyBytes);
            writeOwnerOnly(baseDir.resolve("identity.key"), privateKeyBytes);

            long createdAt = System.currentTimeMillis();
            String meta = displayName + "\n" + createdAt;
            Files.writeString(baseDir.resolve("identity.meta"), meta, StandardCharsets.UTF_8);

            String peerId = derivePeerId(publicKeyBytes);
            return new Identity(peerId, displayName, publicKeyBytes, createdAt);

        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to create identity", e);
        }
    }

    @Override
    public Identity loadIdentity() throws IdentityNotFoundException {
        if (!hasIdentity()) {
            throw new IdentityNotFoundException("No identity found in " + baseDir);
        }
        try {
            byte[] publicKeyBytes = Files.readAllBytes(baseDir.resolve("identity.pub"));
            String[] meta = Files.readString(baseDir.resolve("identity.meta"), StandardCharsets.UTF_8).split("\n");
            String displayName = meta[0];
            long createdAt = Long.parseLong(meta[1]);
            String peerId = derivePeerId(publicKeyBytes);
            return new Identity(peerId, displayName, publicKeyBytes, createdAt);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to load identity", e);
        }
    }

    @Override
    public byte[] rawPrivateKeySeed() {
        if (!hasIdentity()) {
            throw new IllegalStateException("No identity found in " + baseDir + " — call createIdentity() first");
        }
        try {
            byte[] pkcs8Bytes = Files.readAllBytes(baseDir.resolve("identity.key"));
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
            EdECPrivateKeySpec edSpec = keyFactory.getKeySpec(privateKey, EdECPrivateKeySpec.class);
            return edSpec.getBytes();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to extract raw private key seed", e);
        }
    }

    /**
     * Writes {@code bytes} to {@code path} with owner-only permissions ({@code rw-------} / 0600)
     * applied at file-creation time, not via a {@code chmod} afterward — a post-creation chmod
     * leaves a real (if narrow) window where the file exists on disk at default/umask
     * permissions before being locked down, which on a shared or multi-user machine is exactly
     * the exposure this exists to close. See pre-m6h-hardening-plan.md finding C-2.
     *
     * <p>On a filesystem that doesn't support POSIX permissions (Windows, most notably),
     * {@code PosixFilePermissions} isn't usable at all, and there's no direct JDK equivalent for
     * "owner-only" that works the same way — a real fix there means an ACL-based approach, which
     * is out of scope here. Falls back to a plain write and documents the gap rather than
     * throwing, matching the audit's own guidance: this is an interim measure ahead of the
     * OS-keychain integration already tracked as the real, full fix (see README, M0 section).
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

    private String derivePeerId(byte[] publicKeyBytes) throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(publicKeyBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
