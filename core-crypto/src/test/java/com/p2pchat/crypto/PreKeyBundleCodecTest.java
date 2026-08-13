package com.p2pchat.crypto;

import org.junit.jupiter.api.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreKeyBundleCodecTest {

    @Test
    void encodeAndDecodePreKeyBundleRoundTrip() throws Exception {
        IdentityKeyPair identityKeyPair = IdentityKeyPair.generate();
        int registrationId = KeyHelper.generateRegistrationId(false);
        InMemorySignalProtocolStore store = new InMemorySignalProtocolStore(identityKeyPair, registrationId);

        PreKeyBundle original = PreKeyBundleFactory.create(store);

        byte[] wire = PreKeyBundleCodec.encode(original);
        assertThat(wire).isNotEmpty();

        PreKeyBundle decoded = PreKeyBundleCodec.decode(wire);

        assertThat(decoded.getRegistrationId()).isEqualTo(original.getRegistrationId());
        assertThat(decoded.getDeviceId()).isEqualTo(original.getDeviceId());
        assertThat(decoded.getPreKeyId()).isEqualTo(original.getPreKeyId());
        assertThat(decoded.getPreKey().serialize()).isEqualTo(original.getPreKey().serialize());
        assertThat(decoded.getSignedPreKeyId()).isEqualTo(original.getSignedPreKeyId());
        assertThat(decoded.getSignedPreKey().serialize()).isEqualTo(original.getSignedPreKey().serialize());
        assertThat(decoded.getSignedPreKeySignature()).isEqualTo(original.getSignedPreKeySignature());
        assertThat(decoded.getIdentityKey()).isEqualTo(original.getIdentityKey());
        assertThat(decoded.getKyberPreKeyId()).isEqualTo(original.getKyberPreKeyId());
        assertThat(decoded.getKyberPreKey().serialize()).isEqualTo(original.getKyberPreKey().serialize());
        assertThat(decoded.getKyberPreKeySignature()).isEqualTo(original.getKyberPreKeySignature());
    }

    @Test
    void rejectCorruptPreKeyBundleData() {
        byte[] badData = new byte[]{0x00, 0x01, 0x02, 0x03};

        assertThatThrownBy(() -> PreKeyBundleCodec.decode(badData))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to decode PreKeyBundle");
    }
}
