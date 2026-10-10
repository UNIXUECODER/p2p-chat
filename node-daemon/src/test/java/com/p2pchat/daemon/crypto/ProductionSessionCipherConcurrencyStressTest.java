package com.p2pchat.daemon.crypto;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleFactory;
import com.p2pchat.storage.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSessionCipherConcurrencyStressTest {

    private SqliteDatabase aliceDb;
    private SqliteDatabase bobDb;
    private SynchronizedSignalProtocolStore aliceStore;
    private SynchronizedSignalProtocolStore bobStore;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path aliceDir = tempDir.resolve("alice");
        Path bobDir = tempDir.resolve("bob");
        aliceDir.toFile().mkdirs();
        bobDir.toFile().mkdirs();

        aliceDb = SqliteDatabase.openOrCreate(aliceDir);
        bobDb = SqliteDatabase.openOrCreate(bobDir);

        IdentityKeyPair aliceKeyPair = IdentityKeyPair.generate();
        int aliceRegId = KeyHelper.generateRegistrationId(false);
        aliceStore = new SynchronizedSignalProtocolStore(new SqliteSignalProtocolStore(aliceDb, aliceKeyPair, aliceRegId));

        IdentityKeyPair bobKeyPair = IdentityKeyPair.generate();
        int bobRegId = KeyHelper.generateRegistrationId(false);
        bobStore = new SynchronizedSignalProtocolStore(new SqliteSignalProtocolStore(bobDb, bobKeyPair, bobRegId));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (aliceDb != null) {
            aliceDb.close();
        }
        if (bobDb != null) {
            bobDb.close();
        }
    }

    @Test
    void testConcurrentEncryptionOnProductionComposition() throws Exception {
        SignalProtocolAddress aliceAddress = new SignalProtocolAddress("12D3KooWAlice", 1);
        SignalProtocolAddress bobAddress = new SignalProtocolAddress("12D3KooWBob", 1);

        var aliceSessions = new LibsignalSecureSessionService(aliceStore, aliceAddress);
        var bobSessions = new LibsignalSecureSessionService(bobStore, bobAddress);

        // 1. Establish session using production stores
        PreKeyBundle bobBundle = PreKeyBundleFactory.create(bobStore);
        aliceSessions.establishSession(bobAddress, bobBundle);

        // 2. Warm up session
        EncryptedFrame warmUp = aliceSessions.encrypt(bobAddress, "warmup".getBytes(StandardCharsets.UTF_8));
        bobSessions.decrypt(aliceAddress, warmUp);
        EncryptedFrame reply = bobSessions.encrypt(aliceAddress, "reply".getBytes(StandardCharsets.UTF_8));
        aliceSessions.decrypt(bobAddress, reply);

        // 3. Launch 20 concurrent encrypt operations on the same session
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
                    // Prefix with common header to observe CBC IV/key reuse consequences
                    String plaintext = "COMMON_HEADER:message_content_" + index;
                    EncryptedFrame frame = aliceSessions.encrypt(bobAddress, plaintext.getBytes(StandardCharsets.UTF_8));
                    frames.add(frame);
                } catch (Throwable t) {
                    encryptErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Production Store - Concurrent encrypt errors: " + encryptErrors.size());
        System.out.println("Production Store - Encrypted frames count: " + frames.size());

        // 4. Analyze SignalMessage counters and inspect for duplicate counters / key reuse
        Map<Integer, List<EncryptedFrame>> framesByCounter = new HashMap<>();
        for (EncryptedFrame frame : frames) {
            SignalMessage sm = new SignalMessage(frame.ciphertext());
            int counter = sm.getCounter();
            framesByCounter.computeIfAbsent(counter, k -> new ArrayList<>()).add(frame);
        }

        System.out.println("SignalMessage unique counters observed: " + framesByCounter.size() + " out of " + frames.size() + " messages");
        int duplicateCounterOccurrences = 0;
        for (Map.Entry<Integer, List<EncryptedFrame>> entry : framesByCounter.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicateCounterOccurrences += (entry.getValue().size() - 1);
                System.out.println("  Duplicate counter " + entry.getKey() + " shared by " + entry.getValue().size() + " different messages!");
            }
        }
        System.out.println("Total duplicate counter collisions: " + duplicateCounterOccurrences);

        // 5. Decrypt all frames on Bob's side
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

        System.out.println("Production Store - Bob decryption successes: " + decryptedSuccess.get() + " / " + frames.size());
        System.out.println("Production Store - Bob decryption failures: " + decryptErrors.size());
        for (Throwable t : decryptErrors) {
            System.out.println("  Decrypt error: " + t.getClass().getName() + ": " + t.getMessage());
        }

        assertThat(encryptErrors).isEmpty();
        assertThat(decryptErrors).isEmpty();
        assertThat(duplicateCounterOccurrences).isZero();
        assertThat(decryptedSuccess.get()).isEqualTo(messageCount);
    }
}
