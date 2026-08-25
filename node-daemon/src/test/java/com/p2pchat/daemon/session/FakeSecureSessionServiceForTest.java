package com.p2pchat.daemon.session;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.SecureSessionService;

import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal {@link SecureSessionService} double — only {@link #encrypt} needs to actually work,
 * since {@link SessionManager#handleDecryptedPlaintext} (called directly by the test) reaches it
 * only via the auto-delivery-receipt path. Not testing encryption correctness here at all — that
 * is {@code LibsignalSecureSessionServiceTest}'s job, already covered; this exists purely so the
 * receive pipeline's dispatch/dedup/persistence logic can be exercised without needing real
 * libsignal-client cryptography to do it.
 */
final class FakeSecureSessionServiceForTest implements SecureSessionService {

    private final List<byte[]> encryptedPlaintexts = new CopyOnWriteArrayList<>();

    List<byte[]> encryptedPlaintexts() {
        return List.copyOf(encryptedPlaintexts);
    }

    @Override
    public void establishSession(SignalProtocolAddress remote, PreKeyBundle remoteBundle) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public EncryptedFrame encrypt(SignalProtocolAddress remote, byte[] plaintext) {
        encryptedPlaintexts.add(plaintext);
        return new EncryptedFrame(false, plaintext);
    }

    @Override
    public byte[] decrypt(SignalProtocolAddress remote, EncryptedFrame frame) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }
}
