package com.p2pchat.daemon.crypto;

import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleFactory;
import com.p2pchat.storage.SqliteDatabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.IdentityKeyStore.Direction;
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteSignalProtocolStoreTest {

    private SqliteDatabase database;
    private SqliteSignalProtocolStore store;
    private final SignalProtocolAddress bob = new SignalProtocolAddress("12D3KooWBob", 1);

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        store = new SqliteSignalProtocolStore(database, IdentityKeyPair.generate(), 12345);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Nested
    class PreKeys {

        @Test
        void roundTrips() throws InvalidKeyIdException {
            PreKeyRecord original = newPreKey(7);
            store.storePreKey(7, original);

            assertThat(store.containsPreKey(7)).isTrue();
            assertThat(store.loadPreKey(7).serialize()).isEqualTo(original.serialize());
        }

        @Test
        void removeActuallyDeletesTheRow() {
            // The actual point of this whole milestone: a consumed one-time pre-key must be
            // physically gone, not soft-deleted, or forward secrecy doesn't survive a restart.
            store.storePreKey(7, newPreKey(7));

            store.removePreKey(7);

            assertThat(store.containsPreKey(7)).isFalse();
        }

        @Test
        void loadingMissingIdThrows() {
            assertThatThrownBy(() -> store.loadPreKey(999)).isInstanceOf(InvalidKeyIdException.class);
        }
    }

    @Nested
    class SignedPreKeys {

        @Test
        void roundTripsAndLists() throws InvalidKeyIdException {
            SignedPreKeyRecord original = newSignedPreKey(3);
            store.storeSignedPreKey(3, original);

            assertThat(store.containsSignedPreKey(3)).isTrue();
            List<SignedPreKeyRecord> all = store.loadSignedPreKeys();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).serialize()).isEqualTo(original.serialize());
            assertThat(store.loadSignedPreKey(3).serialize()).isEqualTo(original.serialize());
        }
    }

    @Nested
    class KyberPreKeys {

        @Test
        void markUsedDoesNotDeleteUnlikeRemovePreKey() throws Exception {
            // PQXDH Kyber pre-keys are last-resort/reusable, unlike one-time EC pre-keys --
            // markKyberPreKeyUsed and removePreKey are different SPI methods for exactly this
            // reason. See V002__signal_store.sql's schema comment for the full reasoning.
            store.storeKyberPreKey(11, newKyberPreKey(11));

            store.markKyberPreKeyUsed(11, 3, newECPublicKey());

            assertThat(store.containsKyberPreKey(11)).isTrue();
        }

        @Test
        void firstUseOfABaseKeyIsAccepted() throws Exception {
            store.storeKyberPreKey(11, newKyberPreKey(11));

            assertThatCode(() -> store.markKyberPreKeyUsed(11, 3, newECPublicKey())).doesNotThrowAnyException();
        }

        @Test
        void replayingTheSameBaseKeyBytesViaADifferentObjectInstanceIsRejected() throws Exception {
            // The realistic case: a base key deserialized twice from two separate wire messages
            // is two different ECPublicKey objects with identical bytes -- exactly what a first
            // draft relying on Set<ECPublicKey>'s default (identity-based) equals() would miss.
            // See V003__kyber_base_key_replay.sql's comment for the full account.
            store.storeKyberPreKey(11, newKyberPreKey(11));
            ECKeyPair keyPair = ECKeyPair.generate();
            ECPublicKey baseKeyInstance1 = keyPair.getPublicKey();
            ECPublicKey baseKeyInstance2 = new ECPublicKey(baseKeyInstance1.serialize());

            store.markKyberPreKeyUsed(11, 3, baseKeyInstance1);

            assertThatThrownBy(() -> store.markKyberPreKeyUsed(11, 3, baseKeyInstance2))
                    .isInstanceOf(ReusedBaseKeyException.class);
        }

        @Test
        void aDifferentBaseKeyForTheSamePairIsAccepted() throws Exception {
            store.storeKyberPreKey(11, newKyberPreKey(11));
            store.markKyberPreKeyUsed(11, 3, newECPublicKey());

            assertThatCode(() -> store.markKyberPreKeyUsed(11, 3, newECPublicKey())).doesNotThrowAnyException();
        }

        @Test
        void theSameBaseKeyBytesForADifferentSignedPreKeyIdIsAccepted() throws Exception {
            // Composite key (kyberPreKeyId, signedPreKeyId, baseKey), not just the base key alone.
            store.storeKyberPreKey(11, newKyberPreKey(11));
            ECPublicKey baseKey = newECPublicKey();
            store.markKyberPreKeyUsed(11, 3, baseKey);

            assertThatCode(() -> store.markKyberPreKeyUsed(11, 4, new ECPublicKey(baseKey.serialize()))).doesNotThrowAnyException();
        }
    }

    @Nested
    class Sessions {

        @Test
        void loadingASessionForABrandNewPeerReturnsFreshNotNullOrThrow() {
            // libsignal's own SessionBuilder/SessionCipher call loadSession as their starting
            // point for every session, including the very first message ever exchanged with a
            // new peer -- null or an exception here would break session establishment outright.
            SessionRecord session = store.loadSession(bob);

            assertThat(session).isNotNull();
        }

        @Test
        void roundTrips() throws Exception {
            SessionRecord original = newPopulatedSessionRecord();
            store.storeSession(bob, original);

            assertThat(store.containsSession(bob)).isTrue();
            byte[] loadedBytes = store.loadSession(bob).serialize();
            assertThat(loadedBytes).isEqualTo(original.serialize());
            assertThat(loadedBytes.length).isGreaterThan(50); // Proves it's a real populated session record, not an empty fallback
        }

        @Test
        void getSubDeviceSessionsFindsTheDeviceId() {
            store.storeSession(bob, new SessionRecord());

            assertThat(store.getSubDeviceSessions("12D3KooWBob")).containsExactly(1);
        }

        @Test
        void deleteRemovesIt() {
            store.storeSession(bob, new SessionRecord());

            store.deleteSession(bob);

            assertThat(store.containsSession(bob)).isFalse();
        }

        @Test
        void loadExistingSessionsThrowsForAMissingAddress() {
            store.storeSession(bob, new SessionRecord());
            SignalProtocolAddress nobody = new SignalProtocolAddress("nobody", 1);

            // Deliberately the opposite contract from loadSession: this method is for "these
            // must already exist", so a missing one is a real error, not a fresh-start signal.
            assertThatThrownBy(() -> store.loadExistingSessions(List.of(bob, nobody)))
                    .isInstanceOf(NoSessionException.class);
        }
    }

    @Nested
    class Identities {

        @Test
        void trustOnFirstUseTrustsAnUnseenAddress() {
            IdentityKey firstKey = newIdentityKey();

            assertThat(store.isTrustedIdentity(bob, firstKey, Direction.RECEIVING)).isTrue();
        }

        @Test
        void savingAnUnseenIdentityReportsNoChange() {
            assertThat(store.saveIdentity(bob, newIdentityKey())).isEqualTo(IdentityChange.NEW_OR_UNCHANGED);
        }

        @Test
        void savingTheSameIdentityAgainReportsNoChange() {
            IdentityKey key = newIdentityKey();
            store.saveIdentity(bob, key);

            assertThat(store.saveIdentity(bob, key)).isEqualTo(IdentityChange.NEW_OR_UNCHANGED);
        }

        @Test
        void aDifferentKeyForAKnownAddressIsUntrustedAndReportsARealChange() {
            // This is the actual MITM-key-swap detection path -- a different identity for an
            // address this store has already pinned one for must be both untrusted and flagged
            // as a real change, not silently accepted.
            IdentityKey originalKey = newIdentityKey();
            store.saveIdentity(bob, originalKey);
            IdentityKey swapped = newIdentityKey();

            assertThat(store.isTrustedIdentity(bob, swapped, Direction.RECEIVING)).isFalse();
            assertThat(store.saveIdentity(bob, swapped)).isEqualTo(IdentityChange.REPLACED_EXISTING);
        }
    }

    @Nested
    class RestartSurvival {

        @Test
        void sessionsPreKeysAndIdentitiesSurviveAFreshConnectionAgainstTheSameDirectory(@TempDir Path tempDir)
                throws Exception {
            SqliteDatabase first = SqliteDatabase.openOrCreate(tempDir);
            SqliteSignalProtocolStore firstStore =
                    new SqliteSignalProtocolStore(first, IdentityKeyPair.generate(), 1);

            SignedPreKeyRecord signedPreKey = newSignedPreKey(3);
            PreKeyRecord preKey = newPreKey(7);
            SessionRecord session = new SessionRecord();
            IdentityKey identity = newIdentityKey();

            firstStore.storeSignedPreKey(3, signedPreKey);
            firstStore.storeSession(bob, session);
            firstStore.storePreKey(7, preKey);
            firstStore.removePreKey(7); // consumed before "restart" -- must not come back
            firstStore.saveIdentity(bob, identity);
            first.close();

            // A genuinely new connection against the same directory -- not the same object,
            // not the same in-memory state, the actual thing a daemon restart looks like.
            SqliteDatabase reopened = SqliteDatabase.openOrCreate(tempDir);
            SqliteSignalProtocolStore reopenedStore =
                    new SqliteSignalProtocolStore(reopened, IdentityKeyPair.generate(), 1);

            assertThat(reopenedStore.containsSignedPreKey(3)).isTrue();
            assertThat(reopenedStore.containsSession(bob)).isTrue();
            assertThat(reopenedStore.containsPreKey(7)).isFalse();
            assertThat(reopenedStore.getIdentity(bob).serialize()).isEqualTo(identity.serialize());

            reopened.close();
        }

        @Test
        void reusedBaseKeyDetectionSurvivesAFreshConnectionAgainstTheSameDirectory(@TempDir Path tempDir)
                throws Exception {
            // The actual point of V003: was replay protection only ever good for one process
            // lifetime? A fresh ECPublicKey instance with the same bytes as one used before the
            // "restart" is the realistic replay shape -- not the same Java object, same key.
            SqliteDatabase first = SqliteDatabase.openOrCreate(tempDir);
            SqliteSignalProtocolStore firstStore = new SqliteSignalProtocolStore(first, IdentityKeyPair.generate(), 1);
            firstStore.storeKyberPreKey(11, newKyberPreKey(11));
            ECPublicKey baseKey = newECPublicKey();
            firstStore.markKyberPreKeyUsed(11, 3, baseKey);
            first.close();

            SqliteDatabase reopened = SqliteDatabase.openOrCreate(tempDir);
            SqliteSignalProtocolStore reopenedStore =
                    new SqliteSignalProtocolStore(reopened, IdentityKeyPair.generate(), 1);

            ECPublicKey replayedBaseKey = new ECPublicKey(baseKey.serialize());
            assertThatThrownBy(() -> reopenedStore.markKyberPreKeyUsed(11, 3, replayedBaseKey))
                    .isInstanceOf(ReusedBaseKeyException.class);
            assertThatCode(() -> reopenedStore.markKyberPreKeyUsed(11, 3, newECPublicKey()))
                    .doesNotThrowAnyException();

            reopened.close();
        }

        @Test
        void endToEndPqxdhSessionAndDoubleRatchetSurvivesSimulatedDaemonRestartOfBothPeers(
                @TempDir Path aliceDir, @TempDir Path bobDir) throws Exception {
            IdentityKeyPair aliceIdentity = IdentityKeyPair.generate();
            IdentityKeyPair bobIdentity = IdentityKeyPair.generate();
            SignalProtocolAddress aliceAddress = new SignalProtocolAddress("alice-peer", 1);
            SignalProtocolAddress bobAddress = new SignalProtocolAddress("bob-peer", 1);

            // Phase 1: Alice and Bob establish a real PQXDH session backed by real SQLite databases.
            SqliteDatabase aliceDb = SqliteDatabase.openOrCreate(aliceDir);
            SqliteDatabase bobDb = SqliteDatabase.openOrCreate(bobDir);

            SqliteSignalProtocolStore aliceStore = new SqliteSignalProtocolStore(aliceDb, aliceIdentity, 1001);
            SqliteSignalProtocolStore bobStore = new SqliteSignalProtocolStore(bobDb, bobIdentity, 2002);

            var bobBundle = PreKeyBundleFactory.create(bobStore);

            var aliceSessions = new LibsignalSecureSessionService(aliceStore, aliceAddress);
            var bobSessions = new LibsignalSecureSessionService(bobStore, bobAddress);

            aliceSessions.establishSession(bobAddress, bobBundle);

            byte[] message1 = "Hello Bob before restart".getBytes(StandardCharsets.UTF_8);
            var frame1 = aliceSessions.encrypt(bobAddress, message1);
            assertThat(frame1.isPreKeyMessage()).isTrue();

            byte[] decrypted1 = bobSessions.decrypt(aliceAddress, frame1);
            assertThat(decrypted1).isEqualTo(message1);

            // Phase 2: full shutdown of both peers -- close JDBC, discard every in-memory object.
            aliceDb.close();
            bobDb.close();

            // Phase 3: reopen both databases from disk with brand-new stores and session services.
            SqliteDatabase reopenedAliceDb = SqliteDatabase.openOrCreate(aliceDir);
            SqliteDatabase reopenedBobDb = SqliteDatabase.openOrCreate(bobDir);

            SqliteSignalProtocolStore reopenedAliceStore =
                    new SqliteSignalProtocolStore(reopenedAliceDb, aliceIdentity, 1001);
            SqliteSignalProtocolStore reopenedBobStore =
                    new SqliteSignalProtocolStore(reopenedBobDb, bobIdentity, 2002);

            var reopenedAliceSessions = new LibsignalSecureSessionService(reopenedAliceStore, aliceAddress);
            var reopenedBobSessions = new LibsignalSecureSessionService(reopenedBobStore, bobAddress);

            // Bob replies over the persisted Double Ratchet without establishing a new session.
            byte[] reply = "Hello Alice after restart!".getBytes(StandardCharsets.UTF_8);
            var replyFrame = reopenedBobSessions.encrypt(aliceAddress, reply);
            assertThat(replyFrame.isPreKeyMessage()).isFalse(); // pure Whisper message, not PreKey

            byte[] decryptedReply = reopenedAliceSessions.decrypt(bobAddress, replyFrame);
            assertThat(decryptedReply).isEqualTo(reply);

            // Alice replies again -- the ratchet keeps advancing across the restart boundary.
            byte[] followup = "Ratchet continued successfully!".getBytes(StandardCharsets.UTF_8);
            var followupFrame = reopenedAliceSessions.encrypt(bobAddress, followup);
            assertThat(followupFrame.isPreKeyMessage()).isFalse();

            byte[] decryptedFollowup = reopenedBobSessions.decrypt(aliceAddress, followupFrame);
            assertThat(decryptedFollowup).isEqualTo(followup);

            reopenedAliceDb.close();
            reopenedBobDb.close();
        }
    }

    @Nested
    class Concurrency {

        @Test
        void synchronizedWrapperSurvivesConcurrentLoadWithoutErrors() throws InterruptedException {
            var concurrentStore = new SynchronizedSignalProtocolStore(store);
            int threadCount = 16, opsPerThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            SignalProtocolAddress addr = new SignalProtocolAddress("peer-" + threadId, 1);
                            concurrentStore.storeSession(addr, new SessionRecord());
                            concurrentStore.loadSession(addr);
                            concurrentStore.containsSession(addr);
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(errors.get()).isZero();
        }
    }

    private static PreKeyRecord newPreKey(int id) {
        return new PreKeyRecord(id, ECKeyPair.generate());
    }

    private static SignedPreKeyRecord newSignedPreKey(int id) {
        ECKeyPair kp = ECKeyPair.generate();
        IdentityKeyPair idKp = IdentityKeyPair.generate();
        byte[] sig = idKp.getPrivateKey().calculateSignature(kp.getPublicKey().serialize());
        return new SignedPreKeyRecord(id, System.currentTimeMillis(), kp, sig);
    }

    private static KyberPreKeyRecord newKyberPreKey(int id) {
        KEMKeyPair kkp = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        IdentityKeyPair idKp = IdentityKeyPair.generate();
        byte[] sig = idKp.getPrivateKey().calculateSignature(kkp.getPublicKey().serialize());
        return new KyberPreKeyRecord(id, System.currentTimeMillis(), kkp, sig);
    }

    private static IdentityKey newIdentityKey() {
        return new IdentityKey(ECKeyPair.generate().getPublicKey());
    }

    private static ECPublicKey newECPublicKey() {
        return ECKeyPair.generate().getPublicKey();
    }

    private static SessionRecord newPopulatedSessionRecord() throws Exception {
        var tempStore = new org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore(
                IdentityKeyPair.generate(), 1);
        SignalProtocolAddress remote = new SignalProtocolAddress("temp-peer", 1);
        var bundle = PreKeyBundleFactory.create(tempStore);
        var sessionService = new LibsignalSecureSessionService(tempStore, new SignalProtocolAddress("local", 1));
        sessionService.establishSession(remote, bundle);
        return tempStore.loadSession(remote);
    }
}
