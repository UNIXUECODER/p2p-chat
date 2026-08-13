package com.p2pchat.crypto;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * PreKeyBundle is a native-handle-backed object (JNI) exposing only individual
 * field getters — confirmed against the real v0.94.0 source, it has no
 * serialize()/deserialize() of its own. This packs every field in the same
 * order as the verified 11-arg PreKeyBundle constructor (used already in
 * M2a), using length-prefixed fields for anything variable-size.
 *
 * Assumes the one-time EC pre-key (getPreKey()) is always present — true for
 * every bundle this project creates via PreKeyBundleFactory. libsignal itself
 * allows it to be optional; this codec deliberately does not, to keep this
 * first version simple.
 */
public final class PreKeyBundleCodec {

    private PreKeyBundleCodec() {
    }

    public static byte[] encode(PreKeyBundle bundle) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);

            out.writeInt(bundle.getRegistrationId());
            out.writeInt(bundle.getDeviceId());
            out.writeInt(bundle.getPreKeyId());
            writeBytes(out, bundle.getPreKey().serialize());
            out.writeInt(bundle.getSignedPreKeyId());
            writeBytes(out, bundle.getSignedPreKey().serialize());
            writeBytes(out, bundle.getSignedPreKeySignature());
            writeBytes(out, bundle.getIdentityKey().serialize());
            out.writeInt(bundle.getKyberPreKeyId());
            writeBytes(out, bundle.getKyberPreKey().serialize());
            writeBytes(out, bundle.getKyberPreKeySignature());

            return buffer.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode PreKeyBundle", e);
        }
    }

    public static PreKeyBundle decode(byte[] data) throws InvalidKeyException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

            int registrationId = in.readInt();
            int deviceId = in.readInt();
            int preKeyId = in.readInt();
            ECPublicKey preKey = new ECPublicKey(readBytes(in));
            int signedPreKeyId = in.readInt();
            ECPublicKey signedPreKey = new ECPublicKey(readBytes(in));
            byte[] signedPreKeySignature = readBytes(in);
            IdentityKey identityKey = new IdentityKey(readBytes(in));
            int kyberPreKeyId = in.readInt();
            KEMPublicKey kyberPreKey = new KEMPublicKey(readBytes(in));
            byte[] kyberPreKeySignature = readBytes(in);

            return new PreKeyBundle(
                    registrationId, deviceId, preKeyId, preKey,
                    signedPreKeyId, signedPreKey, signedPreKeySignature,
                    identityKey, kyberPreKeyId, kyberPreKey, kyberPreKeySignature);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode PreKeyBundle", e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
