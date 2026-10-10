package com.p2pchat.crypto;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.SignalProtocolStore;

/**
 * libsignal implementation of {@link SecureSessionService}.
 * <p>
 * Thread safety &amp; Lock Hierarchy (Rule R-1):
 * libsignal's {@link SessionCipher} states in its Javadoc that "This class is not thread-safe."
 * While {@code SynchronizedSignalProtocolStore} makes individual store calls
 * synchronized, it does not serialize composite load -&gt; ratchet -&gt; store sequences across concurrent
 * operations on the same session.
 * <p>
 * To prevent duplicate message counter derivation and session state corruption (Track F-2), all
 * session operations ({@link #establishSession}, {@link #encrypt}, and {@link #decrypt}) acquire a
 * per-peer {@link ReentrantLock} keyed by the remote address.
 * <p>
 * Lock Hierarchy:
 *   peerCryptoLock(peer) [this lock] -&gt; SynchronizedSignalProtocolStore monitor -&gt; SqliteDatabase lease lock
 * <p>
 * This lock is never held across network I/O (encrypt returns ciphertext; sending happens outside the lock).
 * Note on wire ordering: if two threads encrypt concurrently, thread B may transmit frame n+1 before thread A
 * transmits frame n. libsignal's skipped-key handling natively tolerates out-of-order message arrival.
 * <p>
 * Production must construct exactly one {@code LibsignalSecureSessionService} instance per local node/store;
 * multiple instances sharing a store would bypass instance-level peer locks.
 */
public class LibsignalSecureSessionService implements SecureSessionService {

    private final SignalProtocolStore store;
    private final SignalProtocolAddress localAddress;
    private final ConcurrentMap<String, ReentrantLock> peerLocks = new ConcurrentHashMap<>();

    public LibsignalSecureSessionService(SignalProtocolStore store, SignalProtocolAddress localAddress) {
        this.store = store;
        this.localAddress = localAddress;
    }

    private ReentrantLock lockFor(SignalProtocolAddress remote) {
        return peerLocks.computeIfAbsent(remote.getName() + ":" + remote.getDeviceId(), k -> new ReentrantLock());
    }

    @Override
    public void establishSession(SignalProtocolAddress remote, PreKeyBundle remoteBundle) throws Exception {
        ReentrantLock lock = lockFor(remote);
        lock.lock();
        try {
            SessionBuilder builder = new SessionBuilder(store, remote, localAddress);
            builder.process(remoteBundle);
            store.saveIdentity(remote, remoteBundle.getIdentityKey());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public EncryptedFrame encrypt(SignalProtocolAddress remote, byte[] plaintext) throws Exception {
        ReentrantLock lock = lockFor(remote);
        lock.lock();
        try {
            SessionCipher cipher = new SessionCipher(store, localAddress, remote);
            CiphertextMessage outgoing = cipher.encrypt(plaintext);
            boolean isPreKeyMessage = outgoing.getType() == CiphertextMessage.PREKEY_TYPE;
            return new EncryptedFrame(isPreKeyMessage, outgoing.serialize());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] decrypt(SignalProtocolAddress remote, EncryptedFrame frame) throws Exception {
        ReentrantLock lock = lockFor(remote);
        lock.lock();
        try {
            SessionCipher cipher = new SessionCipher(store, localAddress, remote);
            if (frame.isPreKeyMessage()) {
                PreKeySignalMessage preKeyMessage = new PreKeySignalMessage(frame.ciphertext());
                byte[] plaintext = cipher.decrypt(preKeyMessage);
                store.saveIdentity(remote, preKeyMessage.getIdentityKey());
                return plaintext;
            } else {
                return cipher.decrypt(new SignalMessage(frame.ciphertext()));
            }
        } finally {
            lock.unlock();
        }
    }
}
