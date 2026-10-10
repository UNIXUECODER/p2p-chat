package com.p2pchat.crypto;

import org.junit.jupiter.api.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCipherConcurrencyStressTest {

    @Test
    void concurrentEncryptionOnSameSessionCorruptsRatchetOrFailsDecryption() throws Exception {
        IdentityKeyPair aliceKeyPair = IdentityKeyPair.generate();
        int aliceRegId = KeyHelper.generateRegistrationId(false);
        var aliceStore = new InMemorySignalProtocolStore(aliceKeyPair, aliceRegId);
        var aliceAddress = new SignalProtocolAddress("alice", 1);
        var aliceSessions = new LibsignalSecureSessionService(aliceStore, aliceAddress);

        IdentityKeyPair bobKeyPair = IdentityKeyPair.generate();
        int bobRegId = KeyHelper.generateRegistrationId(false);
        var bobStore = new InMemorySignalProtocolStore(bobKeyPair, bobRegId);
        var bobAddress = new SignalProtocolAddress("bob", 1);
        var bobSessions = new LibsignalSecureSessionService(bobStore, bobAddress);

        // 1. Establish session via Bob's pre-key bundle
        PreKeyBundle bobBundle = PreKeyBundleFactory.create(bobStore);
        aliceSessions.establishSession(bobAddress, bobBundle);

        // 2. Perform warm-up exchange to transition to active Double Ratchet
        EncryptedFrame warmUp = aliceSessions.encrypt(bobAddress, "warmup".getBytes(StandardCharsets.UTF_8));
        bobSessions.decrypt(aliceAddress, warmUp);
        EncryptedFrame reply = bobSessions.encrypt(aliceAddress, "reply".getBytes(StandardCharsets.UTF_8));
        aliceSessions.decrypt(bobAddress, reply);

        // 3. Alice launches 20 concurrent threads to encrypt messages on the SAME session simultaneously
        int messageCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(messageCount);

        List<EncryptedFrame> frames = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> encryptErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < messageCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    EncryptedFrame frame = aliceSessions.encrypt(bobAddress, ("msg " + index).getBytes(StandardCharsets.UTF_8));
                    frames.add(frame);
                } catch (Throwable t) {
                    encryptErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startGate.countDown();
        doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Concurrent encrypt errors: " + encryptErrors.size());
        for (Throwable t : encryptErrors) {
            System.out.println("  Encrypt error: " + t.getClass().getName() + ": " + t.getMessage());
        }

        System.out.println("Total successfully encrypted frames: " + frames.size());

        // 4. Bob attempts to decrypt all frames
        AtomicInteger decryptedSuccess = new AtomicInteger(0);
        List<Throwable> decryptErrors = new ArrayList<>();
        for (EncryptedFrame frame : frames) {
            try {
                byte[] decrypted = bobSessions.decrypt(aliceAddress, frame);
                if (decrypted != null) {
                    decryptedSuccess.incrementAndGet();
                }
            } catch (Throwable t) {
                decryptErrors.add(t);
            }
        }

        System.out.println("Bob decryption successes: " + decryptedSuccess.get() + " / " + frames.size());
        System.out.println("Bob decryption failures: " + decryptErrors.size());
        for (Throwable t : decryptErrors) {
            System.out.println("  Decrypt error: " + t.getClass().getName() + ": " + t.getMessage());
        }

        assertThat(encryptErrors).isEmpty();
        assertThat(decryptErrors).isEmpty();
        assertThat(decryptedSuccess.get()).isEqualTo(messageCount);
    }
}
