package com.p2pchat.crypto;

import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.Medium;

import java.util.Random;

/**
 * Builds a fresh PQXDH pre-key bundle (identity + signed EC pre-key + one-time
 * EC pre-key + Kyber pre-key), storing the corresponding private key material
 * into the given store so a later incoming session-establishment message can
 * be decrypted. Identical logic to M2a's CryptoDemoMain.createPreKeyBundle,
 * relocated here so it's reusable instead of duplicated — not re-derived.
 */
public final class PreKeyBundleFactory {

    private PreKeyBundleFactory() {
    }

    public static PreKeyBundle create(SignalProtocolStore store) throws InvalidKeyException {
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
