package com.p2pchat.daemon.crypto;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.util.List;
import java.util.UUID;

/**
 * M6e-1: wraps any {@link SignalProtocolStore} — {@link SqliteSignalProtocolStore} in practice,
 * but this class knows nothing about SQLite — so every SPI call is serialized on one monitor.
 *
 * <p><b>Why this needs to exist at all.</b> libsignal's own reference {@code
 * InMemorySignalProtocolStore} isn't documented as thread-safe, and through M5e that never
 * mattered — every demo Main only ever has one session active at a time. M6's whole point is
 * concurrent sessions from multiple peers arriving on Netty's I/O threads simultaneously, which
 * is a genuinely new situation this project hasn't had before. Without this wrapper, two peers
 * messaging at the same moment could race on {@code SqliteSignalProtocolStore}'s single shared
 * JDBC {@code Connection} — most JDBC drivers, sqlite-jdbc included, do not guarantee a
 * {@code Connection} is safe to use from multiple threads concurrently without external
 * synchronization, independently of whatever locking SQLite itself does at the file level.
 *
 * <p><b>Why coarse-grained (one lock for every method) instead of per-address locking.</b>
 * Correctness matters far more than throughput here — a chat daemon handling human-paced
 * messaging has no realistic contention problem a single monitor would create, and per-address
 * locking is real additional complexity (lock striping, avoiding deadlock between two peers
 * whose sessions happen to hash to the same stripe) bought for a performance problem that
 * doesn't exist yet. If profiling ever says otherwise, this is a self-contained class to revisit
 * — nothing else in M6 depends on its internals, only on it correctly implementing
 * {@code SignalProtocolStore}.
 *
 * <p>Deliberately a separate decorator rather than synchronization baked into {@code
 * SqliteSignalProtocolStore} directly: that class's own correctness (byte-for-byte round-
 * tripping every record type, the OPK-deletion/Kyber-mark-used distinction, TOFU identity
 * trust) is the hard, hand-traced part; keeping locking orthogonal means a bug in one is never
 * confused for a bug in the other, and this wrapper is equally reusable over {@code
 * InMemorySignalProtocolStore} if a test ever wants that.
 */
public final class SynchronizedSignalProtocolStore implements SignalProtocolStore {

    private final SignalProtocolStore delegate;
    private final Object lock = new Object();

    public SynchronizedSignalProtocolStore(SignalProtocolStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        synchronized (lock) {
            return delegate.getIdentityKeyPair();
        }
    }

    @Override
    public int getLocalRegistrationId() {
        synchronized (lock) {
            return delegate.getLocalRegistrationId();
        }
    }

    @Override
    public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        synchronized (lock) {
            return delegate.saveIdentity(address, identityKey);
        }
    }

    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
        synchronized (lock) {
            return delegate.isTrustedIdentity(address, identityKey, direction);
        }
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        synchronized (lock) {
            return delegate.getIdentity(address);
        }
    }

    @Override
    public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        synchronized (lock) {
            return delegate.loadPreKey(preKeyId);
        }
    }

    @Override
    public void storePreKey(int preKeyId, PreKeyRecord record) {
        synchronized (lock) {
            delegate.storePreKey(preKeyId, record);
        }
    }

    @Override
    public boolean containsPreKey(int preKeyId) {
        synchronized (lock) {
            return delegate.containsPreKey(preKeyId);
        }
    }

    @Override
    public void removePreKey(int preKeyId) {
        synchronized (lock) {
            delegate.removePreKey(preKeyId);
        }
    }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
        synchronized (lock) {
            return delegate.loadSignedPreKey(signedPreKeyId);
        }
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        synchronized (lock) {
            return delegate.loadSignedPreKeys();
        }
    }

    @Override
    public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        synchronized (lock) {
            delegate.storeSignedPreKey(signedPreKeyId, record);
        }
    }

    @Override
    public boolean containsSignedPreKey(int signedPreKeyId) {
        synchronized (lock) {
            return delegate.containsSignedPreKey(signedPreKeyId);
        }
    }

    @Override
    public void removeSignedPreKey(int signedPreKeyId) {
        synchronized (lock) {
            delegate.removeSignedPreKey(signedPreKeyId);
        }
    }

    @Override
    public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
        synchronized (lock) {
            return delegate.loadKyberPreKey(kyberPreKeyId);
        }
    }

    @Override
    public List<KyberPreKeyRecord> loadKyberPreKeys() {
        synchronized (lock) {
            return delegate.loadKyberPreKeys();
        }
    }

    @Override
    public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        synchronized (lock) {
            delegate.storeKyberPreKey(kyberPreKeyId, record);
        }
    }

    @Override
    public boolean containsKyberPreKey(int kyberPreKeyId) {
        synchronized (lock) {
            return delegate.containsKyberPreKey(kyberPreKeyId);
        }
    }

    @Override
    public void markKyberPreKeyUsed(int kyberPreKeyId, int signedPreKeyId, ECPublicKey baseKey) throws ReusedBaseKeyException {
        synchronized (lock) {
            delegate.markKyberPreKeyUsed(kyberPreKeyId, signedPreKeyId, baseKey);
        }
    }

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        synchronized (lock) {
            return delegate.loadSession(address);
        }
    }

    @Override
    public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        synchronized (lock) {
            return delegate.loadExistingSessions(addresses);
        }
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        synchronized (lock) {
            return delegate.getSubDeviceSessions(name);
        }
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        synchronized (lock) {
            delegate.storeSession(address, record);
        }
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        synchronized (lock) {
            return delegate.containsSession(address);
        }
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        synchronized (lock) {
            delegate.deleteSession(address);
        }
    }

    @Override
    public void deleteAllSessions(String name) {
        synchronized (lock) {
            delegate.deleteAllSessions(name);
        }
    }

    @Override
    public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        synchronized (lock) {
            delegate.storeSenderKey(sender, distributionId, record);
        }
    }

    @Override
    public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        synchronized (lock) {
            return delegate.loadSenderKey(sender, distributionId);
        }
    }
}
