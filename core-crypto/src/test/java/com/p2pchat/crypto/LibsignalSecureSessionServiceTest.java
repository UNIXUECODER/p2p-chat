package com.p2pchat.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsignalSecureSessionServiceTest {

    private InMemorySignalProtocolStore aliceStore;
    private InMemorySignalProtocolStore bobStore;

    private SignalProtocolAddress aliceAddress;
    private SignalProtocolAddress bobAddress;

    private SecureSessionService aliceSessions;
    private SecureSessionService bobSessions;

    @BeforeEach
    void setUp() {
        IdentityKeyPair aliceKeyPair = IdentityKeyPair.generate();
        int aliceRegId = KeyHelper.generateRegistrationId(false);
        aliceStore = new InMemorySignalProtocolStore(aliceKeyPair, aliceRegId);
        aliceAddress = new SignalProtocolAddress("alice-peer-id", 1);
        aliceSessions = new LibsignalSecureSessionService(aliceStore, aliceAddress);

        IdentityKeyPair bobKeyPair = IdentityKeyPair.generate();
        int bobRegId = KeyHelper.generateRegistrationId(false);
        bobStore = new InMemorySignalProtocolStore(bobKeyPair, bobRegId);
        bobAddress = new SignalProtocolAddress("bob-peer-id", 1);
        bobSessions = new LibsignalSecureSessionService(bobStore, bobAddress);
    }

    @Test
    void encryptFailsWithoutEstablishedSession() {
        byte[] plaintext = "Hello Bob!".getBytes(StandardCharsets.UTF_8);

        // Encrypting without prior establishSession must fail
        assertThatThrownBy(() -> aliceSessions.encrypt(bobAddress, plaintext))
                .isInstanceOf(Exception.class);
    }

    @Test
    void establishSessionAndExchangeEncryptedMessages() throws Exception {
        // Bob creates and publishes a pre-key bundle
        PreKeyBundle bobBundle = PreKeyBundleFactory.create(bobStore);

        // Alice establishes a PQXDH session using Bob's bundle
        aliceSessions.establishSession(bobAddress, bobBundle);

        // Alice encrypts initial message 1 for Bob
        byte[] plaintext1 = "Hello Bob! This is message 1 from Alice.".getBytes(StandardCharsets.UTF_8);
        EncryptedFrame frame1 = aliceSessions.encrypt(bobAddress, plaintext1);
        assertThat(frame1.isPreKeyMessage()).isTrue(); // First message is PreKeyWhisperMessage

        // Bob decrypts Alice's message 1 (which automatically builds Bob's session for Alice)
        byte[] decrypted1 = bobSessions.decrypt(aliceAddress, frame1);
        assertThat(decrypted1).isEqualTo(plaintext1);

        // Bob replies back to Alice on the active session
        byte[] replyPlaintext = "Hi Alice! Reply from Bob.".getBytes(StandardCharsets.UTF_8);
        EncryptedFrame replyFrame = bobSessions.encrypt(aliceAddress, replyPlaintext);

        byte[] decryptedReply = aliceSessions.decrypt(bobAddress, replyFrame);
        assertThat(decryptedReply).isEqualTo(replyPlaintext);

        // Now that the handshake round trip is complete, Alice sends message 2 on the active Double Ratchet session
        byte[] plaintext2 = "Hello Bob! This is message 2 from Alice.".getBytes(StandardCharsets.UTF_8);
        EncryptedFrame frame2 = aliceSessions.encrypt(bobAddress, plaintext2);
        assertThat(frame2.isPreKeyMessage()).isFalse(); // Subsequent messages after acknowledgment are standard WhisperMessages

        byte[] decrypted2 = bobSessions.decrypt(aliceAddress, frame2);
        assertThat(decrypted2).isEqualTo(plaintext2);
    }
}
