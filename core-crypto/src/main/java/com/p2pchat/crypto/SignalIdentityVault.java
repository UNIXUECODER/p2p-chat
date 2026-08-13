package com.p2pchat.crypto;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

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

            Files.write(keyFile, keyPair.serialize());
            Files.write(regFile, ByteBuffer.allocate(4).putInt(registrationId).array());

            return new SignalIdentity(keyPair, registrationId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create Signal identity in " + dataDir, e);
        }
    }
}
