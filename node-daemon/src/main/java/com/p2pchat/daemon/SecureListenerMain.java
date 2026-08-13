package com.p2pchat.daemon;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.PreKeyBundleFactory;
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

public class SecureListenerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;

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

        // Fresh pre-keys every run — matches the already-verified M2a pattern.
        // Only the identity itself (above) is persistent; see SignalIdentityVault.
        PreKeyBundle bundle = PreKeyBundleFactory.create(signalStore);
        String bundleBase64 = Base64.getEncoder().encodeToString(PreKeyBundleCodec.encode(bundle));
        Path bundleFile = baseDir.resolve("published-bundle.b64");
        Files.writeString(bundleFile, bundleBase64);

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            try {
                EncryptedFrame frame = EncryptedFrameCodec.decode(data);
                SignalProtocolAddress remote = new SignalProtocolAddress(sender.toString(), 1);
                byte[] plaintext = sessions.decrypt(remote, frame);
                System.out.println("[secure] decrypted from " + sender + ": \""
                        + new String(plaintext, StandardCharsets.UTF_8) + "\"");
            } catch (Exception e) {
                System.out.println("[secure] FAILED to decrypt message from " + sender + ": " + e);
            }
        });

        System.out.println("App identity    : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Data dir        : " + baseDir);
        System.out.println();
        System.out.println("Give the sender BOTH of these:");
        System.out.println();
        System.out.println("1) Network address:");
        for (String addr : network.listenAddresses()) {
            System.out.println("   " + addr);
        }
        System.out.println();
        System.out.println("2) Pre-key bundle file (changes every restart — always use the latest):");
        System.out.println("   " + bundleFile);
        System.out.println("   (or paste its contents directly — it's a single Base64 line)");
        System.out.println();
        System.out.println("Waiting for a secure message. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }
}
