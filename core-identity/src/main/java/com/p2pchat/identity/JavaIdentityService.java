package com.p2pchat.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;

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
            Files.write(baseDir.resolve("identity.key"), privateKeyBytes);

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
