package com.p2pchat.daemon;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;
import org.signal.libsignal.protocol.util.Medium;

import java.util.Random;

/**
 * M2a: proves the real Signal Protocol (PQXDH session establishment + Double
 * Ratchet encryption) works correctly, entirely in memory. No networking —
 * Bob's pre-key bundle is handed to Alice directly in this process, standing
 * in for what M2b/M2c will do over an actual libp2p connection.
 */
public class CryptoDemoMain {

    public static void main(String[] args) throws Exception {
        SignalProtocolAddress aliceAddress = new SignalProtocolAddress("alice", 1);
        SignalProtocolAddress bobAddress = new SignalProtocolAddress("bob", 1);

        SignalProtocolStore aliceStore = newInMemoryStore();
        SignalProtocolStore bobStore = newInMemoryStore();

        // In the real app, Bob would publish this bundle via core-discovery/relay-server
        // (per docs/architecture-spec.md §10) and Alice would fetch it from there.
        PreKeyBundle bobBundle = createPreKeyBundle(bobStore);

        // --- This is the actual PQXDH handshake step ---
        SessionBuilder aliceSessionBuilder = new SessionBuilder(aliceStore, bobAddress, aliceAddress);
        aliceSessionBuilder.process(bobBundle);
        System.out.println("Alice established a PQXDH session with Bob using his pre-key bundle.");

        SessionCipher aliceCipher = new SessionCipher(aliceStore, aliceAddress, bobAddress);
        String message1 = "Hello Bob, this is the first encrypted message.";
        CiphertextMessage outgoing1 = aliceCipher.encrypt(message1.getBytes());
        boolean isPreKeyType = outgoing1.getType() == CiphertextMessage.PREKEY_TYPE;
        System.out.println("Alice encrypted message 1. Type is PREKEY (handshake-carrying): " + isPreKeyType);

        // Bob decrypts it — this implicitly completes his side of the session too.
        SessionCipher bobCipher = new SessionCipher(bobStore, bobAddress, aliceAddress);
        byte[] decrypted1 = bobCipher.decrypt(new PreKeySignalMessage(outgoing1.serialize()));
        boolean roundTrip1Ok = message1.equals(new String(decrypted1));
        System.out.println("Bob decrypted: \"" + new String(decrypted1) + "\"  (correct: " + roundTrip1Ok + ")");

        // --- Double Ratchet: Bob replies, no handshake needed this time ---
        String message2 = "Hi Alice, got it — Double Ratchet is working.";
        CiphertextMessage outgoing2 = bobCipher.encrypt(message2.getBytes());
        boolean isWhisperType = outgoing2.getType() == CiphertextMessage.WHISPER_TYPE;
        System.out.println("Bob encrypted message 2. Type is WHISPER (ratchet-only): " + isWhisperType);

        byte[] decrypted2 = aliceCipher.decrypt(new SignalMessage(outgoing2.serialize()));
        boolean roundTrip2Ok = message2.equals(new String(decrypted2));
        System.out.println("Alice decrypted: \"" + new String(decrypted2) + "\"  (correct: " + roundTrip2Ok + ")");

        System.out.println();
        boolean allCorrect = isPreKeyType && roundTrip1Ok && isWhisperType && roundTrip2Ok;
        System.out.println(allCorrect
                ? "M2a CONFIRMED: PQXDH session establishment + Double Ratchet encrypt/decrypt work correctly."
                : "M2a FAILED: something did not match — do not proceed to M2b until this is green.");
    }

    private static SignalProtocolStore newInMemoryStore() {
        ECKeyPair identityKeyPairKeys = ECKeyPair.generate();
        IdentityKeyPair identity = new IdentityKeyPair(
                new IdentityKey(identityKeyPairKeys.getPublicKey()), identityKeyPairKeys.getPrivateKey());
        int registrationId = KeyHelper.generateRegistrationId(false);
        return new InMemorySignalProtocolStore(identity, registrationId);
    }

    /** Builds a PQXDH pre-key bundle: identity + signed EC pre-key + one-time EC pre-key + Kyber pre-key. */
    private static PreKeyBundle createPreKeyBundle(SignalProtocolStore store) throws InvalidKeyException {
        ECKeyPair preKeyPair = ECKeyPair.generate();
        ECKeyPair signedPreKeyPair = ECKeyPair.generate();
        byte[] signedPreKeySignature = store.getIdentityKeyPair().getPrivateKey()
                .calculateSignature(signedPreKeyPair.getPublicKey().serialize());

        KEMKeyPair kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[] kyberPreKeySignature = store.getIdentityKeyPair().getPrivateKey()
                .calculateSignature(kyberPreKeyPair.getPublicKey().serialize());

        Random random = new Random();
        int preKeyId = random.nextInt(Medium.MAX_VALUE);
        int signedPreKeyId = random.nextInt(Medium.MAX_VALUE);
        int kyberPreKeyId = random.nextInt(Medium.MAX_VALUE);

        store.storePreKey(preKeyId, new PreKeyRecord(preKeyId, preKeyPair));
        store.storeSignedPreKey(signedPreKeyId, new SignedPreKeyRecord(
                signedPreKeyId, System.currentTimeMillis(), signedPreKeyPair, signedPreKeySignature));
        store.storeKyberPreKey(kyberPreKeyId, new KyberPreKeyRecord(
                kyberPreKeyId, System.currentTimeMillis(), kyberPreKeyPair, kyberPreKeySignature));

        return new PreKeyBundle(
                store.getLocalRegistrationId(),
                1,
                preKeyId,
                preKeyPair.getPublicKey(),
                signedPreKeyId,
                signedPreKeyPair.getPublicKey(),
                signedPreKeySignature,
                store.getIdentityKeyPair().getPublicKey(),
                kyberPreKeyId,
                kyberPreKeyPair.getPublicKey(),
                kyberPreKeySignature);
    }
}
