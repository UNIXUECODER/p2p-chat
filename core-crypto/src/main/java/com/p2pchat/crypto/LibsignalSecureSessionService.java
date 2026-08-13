package com.p2pchat.crypto;

import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.SignalProtocolStore;

public class LibsignalSecureSessionService implements SecureSessionService {

    private final SignalProtocolStore store;
    private final SignalProtocolAddress localAddress;

    public LibsignalSecureSessionService(SignalProtocolStore store, SignalProtocolAddress localAddress) {
        this.store = store;
        this.localAddress = localAddress;
    }

    @Override
    public void establishSession(SignalProtocolAddress remote, PreKeyBundle remoteBundle) throws Exception {
        SessionBuilder builder = new SessionBuilder(store, remote, localAddress);
        builder.process(remoteBundle);
        store.saveIdentity(remote, remoteBundle.getIdentityKey());
    }

    @Override
    public EncryptedFrame encrypt(SignalProtocolAddress remote, byte[] plaintext) throws Exception {
        SessionCipher cipher = new SessionCipher(store, localAddress, remote);
        CiphertextMessage outgoing = cipher.encrypt(plaintext);
        boolean isPreKeyMessage = outgoing.getType() == CiphertextMessage.PREKEY_TYPE;
        return new EncryptedFrame(isPreKeyMessage, outgoing.serialize());
    }

    @Override
    public byte[] decrypt(SignalProtocolAddress remote, EncryptedFrame frame) throws Exception {
        SessionCipher cipher = new SessionCipher(store, localAddress, remote);
        if (frame.isPreKeyMessage()) {
            PreKeySignalMessage preKeyMessage = new PreKeySignalMessage(frame.ciphertext());
            byte[] plaintext = cipher.decrypt(preKeyMessage);
            store.saveIdentity(remote, preKeyMessage.getIdentityKey());
            return plaintext;
        } else {
            return cipher.decrypt(new SignalMessage(frame.ciphertext()));
        }
    }
}
