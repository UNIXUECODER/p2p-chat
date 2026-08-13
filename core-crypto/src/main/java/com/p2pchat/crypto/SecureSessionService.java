package com.p2pchat.crypto;

import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;

/**
 * The SessionCryptoService envisioned in the original architecture spec (§5)
 * — now with an actual caller. Wraps PQXDH session establishment and Double
 * Ratchet encrypt/decrypt behind a small, stable interface so the daemon
 * doesn't need to know libsignal's own class names directly.
 */
public interface SecureSessionService {

    /** Establishes a PQXDH session with the given peer, using their published pre-key bundle. */
    void establishSession(SignalProtocolAddress remote, PreKeyBundle remoteBundle) throws Exception;

    /** Encrypts plaintext for the given peer. A session must already exist (see establishSession). */
    EncryptedFrame encrypt(SignalProtocolAddress remote, byte[] plaintext) throws Exception;

    /**
     * Decrypts a frame from the given peer. If frame.isPreKeyMessage(), this call ALSO
     * establishes the session on this side — the responder never calls establishSession
     * directly; receiving a PreKey-type message does that implicitly, exactly as already
     * verified in the M2a demo (Bob never called establishSession, only decrypt()).
     */
    byte[] decrypt(SignalProtocolAddress remote, EncryptedFrame frame) throws Exception;
}
