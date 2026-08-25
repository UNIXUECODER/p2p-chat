package com.p2pchat.daemon;

import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.PreKeyBundleFactory;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.daemon.crypto.SqliteSignalProtocolStore;
import com.p2pchat.daemon.crypto.SynchronizedSignalProtocolStore;
import com.p2pchat.daemon.session.FileTransferHandler;
import com.p2pchat.daemon.session.SessionManager;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
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
 * M6e-2: the listener side of the first real demo of the actual multi-session daemon core —
 * distinct from {@code ChatListenerMain} (M5c) in the one way that matters for this milestone:
 * this process can hold live sessions with any number of concurrent senders on one listener, not
 * one hardcoded remote. Run this once; run {@code SessionManagerSenderMain} against it as many
 * times, from as many different data directories, as you want to prove that concurrently — the
 * same listener process, unmodified, is the actual capability M6e-2 adds over M5c/M5d.
 *
 * <p>Uses the real persistent store from M6e-1 ({@code SqliteSignalProtocolStore}, wrapped in
 * {@code SynchronizedSignalProtocolStore}) — not {@code InMemorySignalProtocolStore}, which
 * every demo Main through M5e used. This is the actual end-to-end proof that M6e-1's store and
 * M6e-2's session manager work together for real, not two milestones separately compiling.
 *
 * <p>Almost all setup here matches {@code ChatListenerMain} exactly (identity loading, bundle
 * publishing, data directory convention) — deliberately; what's different is everything after
 * that is delegated to {@link SessionManager} instead of being inlined per-demo. One real
 * departure worth naming: {@code ChatListenerMain}'s own local {@code SignalProtocolAddress} was
 * built from {@code identity.peerId()} (the app-identity hex ID), not the libp2p peer ID —
 * checked, and this is almost certainly harmless there, since a local address is an internal
 * label libsignal never validates against anything a remote party sends. Still, {@link
 * SessionManager} deliberately derives its own local address from the real libp2p peer ID
 * throughout, for consistency with every other use of peer identity in this project (conversation
 * IDs, storage's {@code sender_peer_id}, remote addresses) — not because {@code ChatListenerMain}
 * was proven broken, but because this was already flagged as the mismatch to actually fix once a
 * session manager existed to fix it in.
 */
public class SessionManagerListenerMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9300;

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

        // Published before start() -- PreKeyBundleFactory only needs the store, not the running
        // network -- same ordering ChatListenerMain already established.
        PreKeyBundle bundle = PreKeyBundleFactory.create(synchronizedStore);
        String bundleBase64 = Base64.getEncoder().encodeToString(PreKeyBundleCodec.encode(bundle));
        Path bundleFile = baseDir.resolve("published-bundle.b64");
        Files.writeString(bundleFile, bundleBase64);

        StorageService storage = new SqliteStorageService(database);
        PeerNetworkService network = new Libp2pNetworkService();
        FileTransferHandler fileTransferHandler = new FileTransferHandler() {
        }; // file transfer is explicitly out of M6e-2's scope -- see SessionManager's own Javadoc

        SessionManager sessionManager = new SessionManager(network, storage, synchronizedStore, fileTransferHandler);
        sessionManager.start(port, identityService.rawPrivateKeySeed());

        System.out.println("Listening on port " + port + ", peer id " + sessionManager.localPeerId().value());
        System.out.println("Bundle published to " + bundleFile.toAbsolutePath());
        System.out.println("Run SessionManagerSenderMain against this process as many times, from as many");
        System.out.println("different -Ddatadir values, as you want -- one listener, any number of concurrent senders.");
        System.out.println("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(sessionManager::close));
        Thread.currentThread().join(); // stay running until interrupted
    }
}
