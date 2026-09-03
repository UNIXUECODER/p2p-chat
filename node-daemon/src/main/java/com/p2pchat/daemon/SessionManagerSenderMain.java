package com.p2pchat.daemon;

import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.daemon.crypto.SqliteSignalProtocolStore;
import com.p2pchat.daemon.crypto.SynchronizedSignalProtocolStore;
import com.p2pchat.daemon.session.ChatSendResult;
import com.p2pchat.daemon.session.DefaultFileTransferHandler;
import com.p2pchat.daemon.session.FileTransferHandler;
import com.p2pchat.daemon.session.PrintingDaemonEventListener;
import com.p2pchat.daemon.session.SessionManager;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.model.PeerId;
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
 *
 * <p><b>M6g-3 checkpoint amendment.</b> Through M6g-3, this Main constructed a bare {@code new
 * FileTransferHandler() {}} no-op and never passed a {@code DaemonEventListener} at all — so
 * {@code DefaultFileTransferHandler} (M6g-3) and every {@code DaemonEventListener} callback had
 * only ever been exercised against fakes in unit tests, never for real between two processes.
 * Now wires in the real {@link com.p2pchat.daemon.session.DefaultFileTransferHandler} and a
 * real, printing {@link com.p2pchat.daemon.session.PrintingDaemonEventListener} — needed on this
 * side too, since {@link SessionManager#sendFile} routes its own outgoing chunk-serving through
 * the same {@code FileTransferHandler} the listener uses, and a real {@code
 * DaemonEventListener.onDeliveryStateChanged} confirms this process actually saw the chat
 * message's delivery receipt land, not just that {@code Thread.sleep} elapsed. A new, optional
 * {@code -Pfile=<path>} argument sends that file to the listener via {@link
 * SessionManager#sendFile} immediately after the chat message, exercising {@code
 * DefaultFileTransferHandler}'s offer → chunk-request → chunk-serve path end to end for the
 * first time against real hardware, not just its own isolated unit tests.
 */
public class SessionManagerSenderMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runSessionManagerSender \\");
            System.out.println("           -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" \\");
            System.out.println("           -Pbundlefile=\"<path to the listener's published-bundle.b64>\" \\");
            System.out.println("           -Pmessage=\"your message here\" \\");
            System.out.println("           -Pport=9301 (optional; this node's own listening port, needed to receive the receipt back) \\");
            System.out.println("           -Pfile=\"<path to a small local file>\" (optional; also offers this file to the listener,");
            System.out.println("               exercising SessionManager.sendFile/DefaultFileTransferHandler end-to-end -- M6g-3 checkpoint)");
            return;
        }

        String listenerAddress = args[0];
        String bundleFilePath = args[1];
        String messageText = args[2];
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 9301;
        String filePath = args.length > 4 ? args[4] : null;

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

        // M6g-3 checkpoint: real file-transfer handler + real event listener on the sender side
        // too -- SessionManager.sendFile's own outgoing chunk-serving goes through the same
        // FileTransferHandler seam as the listener's inbound path, and this process needs to be
        // a real listener in its own right to ever see the chat message's delivery receipt or
        // (were the direction reversed) an inbound file offer. See SessionManagerListenerMain
        // and PrintingDaemonEventListener's own Javadoc for the full reasoning.
        Path downloadDir = baseDir.resolve("received-files");
        Files.createDirectories(downloadDir);
        PrintingDaemonEventListener eventListener = new PrintingDaemonEventListener(downloadDir);
        FileTransferHandler fileTransferHandler = new DefaultFileTransferHandler(storage, eventListener);

        SessionManager sessionManager =
                new SessionManager(network, storage, synchronizedStore, fileTransferHandler, eventListener);
        eventListener.attachSessionManager(sessionManager);
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

        // M6g-4: sendChatMessage now returns a ChatSendResult (messageId + status), not a bare
        // ConnectivityStatus -- see that class's own Javadoc for why.
        ChatSendResult chatResult = sessionManager
                .sendChatMessage(remotePeerId, listenerAddress, null, bundle, conversationId, messageText)
                .join();

        System.out.println("Send resolved: " + chatResult.status() + " (messageId=" + chatResult.messageId() + ")");
        System.out.println("Waiting up to 5s for the delivery receipt to arrive back...");
        Thread.sleep(5000);
        System.out.println("Done -- check this process's SQLite database for the message's final delivery_state.");

        if (filePath != null) {
            Path fileToSend = Path.of(filePath);
            System.out.println("Offering file " + fileToSend.toAbsolutePath() + " to " + remotePeerIdValue + " ...");
            // No bundle needed here even for a genuine first contact -- sendChatMessage above
            // already established the session (or confirmed one existed) before this point, and
            // SessionManager.sendChatMessage's own bundleIfNoSessionYet parameter is exactly
            // this "only spend a one-time prekey if a session doesn't already exist" gate.
            // M6g-4: sendFile now returns a FileSendResult (transferId + status), not a bare
            // ConnectivityStatus -- see that class's own Javadoc for why.
            var fileResult = sessionManager.sendFile(remotePeerId, listenerAddress, null, fileToSend).join();
            System.out.println("File offer send resolved: " + fileResult.status() + " (transferId=" + fileResult.transferId() + ")");
            System.out.println("Waiting up to 15s for chunk request/response and completion to finish...");
            Thread.sleep(15000);
            System.out.println("Done -- check the listener's own console/output directory for the received file,");
            System.out.println("and this process's console above for [file] chunk-request/chunk-sent lines.");
        }

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
