package com.p2pchat.daemon;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.SecureSessionService;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class SecureSenderMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runSecureSender \\");
            System.out.println("           -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" \\");
            System.out.println("           -Pbundlefile=\"<path to the listener's published-bundle.b64>\" \\");
            System.out.println("           -Pmessage=\"your message here\"");
            return;
        }

        String targetAddress = args[0];
        String bundleFilePath = args[1];
        String messageText = args[2];

        String remotePeerId = extractPeerId(targetAddress);
        String bundleBase64 = Files.readString(Path.of(bundleFilePath)).trim();

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));

        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        SignalIdentity signalIdentity = SignalIdentityVault.loadOrCreate(baseDir);
        InMemorySignalProtocolStore signalStore =
                new InMemorySignalProtocolStore(signalIdentity.keyPair(), signalIdentity.registrationId());
        SignalProtocolAddress localSignalAddress = new SignalProtocolAddress(identity.peerId(), 1);
        SecureSessionService sessions = new LibsignalSecureSessionService(signalStore, localSignalAddress);

        SignalProtocolAddress remoteSignalAddress = new SignalProtocolAddress(remotePeerId, 1);
        PreKeyBundle remoteBundle = PreKeyBundleCodec.decode(Base64.getDecoder().decode(bundleBase64));
        sessions.establishSession(remoteSignalAddress, remoteBundle);
        System.out.println("PQXDH session established with " + remotePeerId);

        EncryptedFrame frame = sessions.encrypt(remoteSignalAddress, messageText.getBytes(StandardCharsets.UTF_8));
        System.out.println("Encrypted message. Type: "
                + (frame.isPreKeyMessage() ? "PREKEY (handshake-carrying)" : "WHISPER (ratchet-only)"));

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { });

        network.sendEnvelope(targetAddress, EncryptedFrameCodec.encode(frame));
        System.out.println("Sent. Check the listener's console for a matching [secure] decrypted line.");

        // Same reasoning as NetworkPingerMain's pause (M2b): give the write time
        // to actually reach the wire before tearing the connection down.
        Thread.sleep(500);

        network.stop();
        System.out.println();
        System.out.println("Done. If the listener printed your exact message text, M2c is proven —");
        System.out.println("PQXDH + Double Ratchet, over a real connection, between two real peers.");
    }

    private static String extractPeerId(String multiaddr) {
        int index = multiaddr.lastIndexOf("/p2p/");
        if (index == -1) {
            throw new IllegalArgumentException("Address does not contain a /p2p/<peer-id> component: " + multiaddr);
        }
        return multiaddr.substring(index + "/p2p/".length());
    }
}
