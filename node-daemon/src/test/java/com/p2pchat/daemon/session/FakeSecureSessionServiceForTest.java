package com.p2pchat.daemon.session;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.SecureSessionService;

import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal {@link SecureSessionService} double — not testing encryption correctness here at
 * all, that is {@code LibsignalSecureSessionServiceTest}'s job, already covered; this exists
 * purely so the receive pipeline's dispatch/dedup/persistence logic can be exercised without
 * needing real libsignal-client cryptography to do it.
 *
 * <p><b>A-2 update: {@link #decrypt} is now a real (if trivial) no-op, not a throw.</b>
 * Previously only {@link #encrypt} needed to work, since {@code
 * SessionManagerReceivePipelineTest} calls {@code handleDecryptedPlaintext} directly, bypassing
 * decryption entirely. {@code SessionManagerRelayInboundTest} goes in through {@code
 * handleInboundEnvelope} instead (the whole point of what it's proving — see that class's own
 * Javadoc), which genuinely calls {@code decrypt} first. Symmetric with {@link #encrypt}'s own
 * no-op transformation — {@code encrypt(plaintext)} wraps it in a non-PreKey {@code
 * EncryptedFrame} unchanged, so {@code decrypt(frame)} unwrapping it via {@code
 * frame.ciphertext()} is the correct inverse, not an arbitrary stand-in.
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
        return frame.ciphertext();
    }
}
