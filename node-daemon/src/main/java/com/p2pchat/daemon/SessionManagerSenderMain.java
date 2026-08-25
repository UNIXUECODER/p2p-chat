package com.p2pchat.daemon;

import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.daemon.crypto.SqliteSignalProtocolStore;
import com.p2pchat.daemon.crypto.SynchronizedSignalProtocolStore;
import com.p2pchat.daemon.session.FileTransferHandler;
import com.p2pchat.daemon.session.SessionManager;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.ConnectivityStatus;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;

import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * M6e-2: the sender side. Sends exactly one chat message via {@link SessionManager#sendChatMessage}
 * against a running {@code SessionManagerListenerMain}, waits briefly to let the listener's
 * auto-delivery-receipt arrive and be processed by this process's own inbound pipeline (the
 * receipt is sent back to whatever this process reports as its {@code senderAddress} — see
 * {@code ChatMessagePayload}'s own Javadoc — so this process must itself be listening, not just
 * sending, to ever see it), then exits.
 *
 * <p>Run this multiple times, from different {@code -Ddatadir} values, against the SAME running
 * listener, to prove the actual new capability M6e-2 adds: one listener holding multiple
 * concurrent, isolated sessions — not a new demo mode, the same two classes, run more than
 * twice.
 */
public class SessionManagerSenderMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runSessionManagerSender \\");
            System.out.println("           -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" \\");
            System.out.println("           -Pbundlefile=\"<path to the listener's published-bundle.b64>\" \\");
            System.out.println("           -Pmessage=\"your message here\" \\");
            System.out.println("           -Pport=9301 (optional; this node's own listening port, needed to receive the receipt back)");
            return;
        }

        String listenerAddress = args[0];
        String bundleFilePath = args[1];
        String messageText = args[2];
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 9301;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));

        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        SignalIdentity signalIdentity = SignalIdentityVault.loadOrCreate(baseDir);
        SqliteDatabase database = SqliteDatabase.openOrCreate(baseDir);
        SqliteSignalProtocolStore signalStore =
                new SqliteSignalProtocolStore(database, signalIdentity.keyPair(), signalIdentity.registrationId());
        var synchronizedStore = new SynchronizedSignalProtocolStore(signalStore);

        StorageService storage = new SqliteStorageService(database);
        PeerNetworkService network = new Libp2pNetworkService();
        FileTransferHandler fileTransferHandler = new FileTransferHandler() {
        };

        SessionManager sessionManager = new SessionManager(network, storage, synchronizedStore, fileTransferHandler);
        sessionManager.start(port, identityService.rawPrivateKeySeed());
        Runtime.getRuntime().addShutdownHook(new Thread(sessionManager::close));

        String remotePeerIdValue = listenerAddress.substring(listenerAddress.lastIndexOf("/p2p/") + "/p2p/".length());
        PeerId remotePeerId = PeerId.of(remotePeerIdValue);

        PreKeyBundle bundle = null;
        if (!sessionManager.hasSession(remotePeerId)) {
            String bundleBase64 = Files.readString(Path.of(bundleFilePath));
            bundle = PreKeyBundleCodec.decode(Base64.getDecoder().decode(bundleBase64));
        }

        String conversationId = deriveDirectConversationId(sessionManager.localPeerId().value(), remotePeerIdValue);
        System.out.println("Sending as " + sessionManager.localPeerId().value() + " to " + remotePeerIdValue + " ...");

        ConnectivityStatus status = sessionManager
                .sendChatMessage(remotePeerId, listenerAddress, null, bundle, conversationId, messageText)
                .join();

        System.out.println("Send resolved: " + status);
        System.out.println("Waiting up to 5s for the delivery receipt to arrive back...");
        Thread.sleep(5000);
        System.out.println("Done -- check this process's SQLite database for the message's final delivery_state.");

        sessionManager.close();
    }

    // Same logic as ChatSenderMain/SessionManager's own private helper -- see SessionManager's
    // own Javadoc for why this stays duplicated rather than extracted.
    private static String deriveDirectConversationId(String peerIdA, String peerIdB) {
        return peerIdA.compareTo(peerIdB) <= 0
                ? "direct-" + peerIdA + "-" + peerIdB
                : "direct-" + peerIdB + "-" + peerIdA;
    }
}
