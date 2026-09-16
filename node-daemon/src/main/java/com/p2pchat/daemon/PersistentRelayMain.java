package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.network.RelaySession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

/**
 * A-1 (pre-m6h-hardening-plan.md, Track A): the demo Main that actually exercises {@link
 * RelaySession} on real hardware — matching this project's own established practice of proving
 * new networking capability by hand, not just by unit test. {@code RelayRegisterMain}/{@code
 * RelayForwardMain} (M3a) are deliberately left untouched, still using a one-shot {@code
 * RelayController} directly — this is the new one, meant to stay running for the audit's own
 * stated verification window: "holds one relay connection across &gt;=30 minutes idle, survives
 * a relay restart via automatic reconnect."
 *
 * <p>Suggested verification, run two of these (different {@code -Pdatadir} each), note the
 * libp2p peer ID each prints, then:
 * <ol>
 *   <li>Leave both idle for &gt;=30 minutes — confirms the keepalive alone (no application
 *   traffic) keeps the connection, and any NAT mapping in between, alive.</li>
 *   <li>Kill and restart the {@code runRelay} process while both stay running — watch for a
 *   "disconnected" line followed, unattended, by a fresh "connected" line.</li>
 *   <li>After that reconnect, send a message from one to the other with {@code -Ptarget}/{@code
 *   -Pmessage} (or just rerun this Main with those flags) — confirms the persistent session, not
 *   just the initial connection, still actually carries traffic.</li>
 * </ol>
 *
 * <p><b>Not exercised by this demo:</b> receiving a message reaching a real daemon's decrypt/
 * dispatch pipeline — {@code downstream} below only prints what arrives. That's A-2, not this.
 */
public class PersistentRelayMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ./gradlew :node-daemon:runPersistentRelay -Prelay=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\" \\");
            System.out.println("           [-Ptarget=\"<libp2p peer ID>\" -Pmessage=\"...\"] [-Pdatadir=.p2p-chat-data]");
            return;
        }
        String relayAddress = args[0];
        String targetPeerId = args.length > 1 && !args[1].isBlank() ? args[1] : null;
        String messageText = args.length > 2 ? args[2] : null;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        RelaySession[] sessionHolder = new RelaySession[1];
        RelayEventHandler hostRelayHandler = new RelayEventHandler() {
            @Override
            public void onConnected(PeerId peerId, RelayController controller) {
                if (sessionHolder[0] != null) {
                    sessionHolder[0].eventHandler().onConnected(peerId, controller);
                }
            }

            @Override
            public void onFrame(PeerId sender, RelayFrame frame) {
                if (sessionHolder[0] != null) {
                    sessionHolder[0].eventHandler().onFrame(sender, frame);
                }
            }

            @Override
            public void onDisconnected(PeerId peerId, RelayController controller) {
                if (sessionHolder[0] != null) {
                    sessionHolder[0].eventHandler().onDisconnected(peerId, controller);
                }
            }
        };

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { }, hostRelayHandler);

        RelayEventHandler downstream = new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }

            @Override
            public void onFrame(PeerId sender, RelayFrame frame) {
                if (!frame.isForwardRequest()) {
                    String text = new String(frame.payload(), StandardCharsets.UTF_8);
                    log("delivered from " + frame.peerId() + ": \"" + text + "\"");
                }
            }

            @Override public void onDisconnected(PeerId peerId, RelayController controller) { }
        };

        // A one-element holder so the connectivity callback (a constructor argument) can read
        // isConnected() off the very RelaySession it's being handed to -- the callback only ever
        // actually runs later (async), by which point sessionHolder[0] is set.
        RelaySession session = new RelaySession(network, relayAddress, downstream,
                () -> log(sessionHolder[0].isConnected() ? "connected (or reconnected)" : "disconnected"));
        sessionHolder[0] = session;

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Give a peer THIS as -Ptarget (the relay keys by libp2p peer ID, not the app identity above):");
        System.out.println("  " + extractPeerId(network.listenAddresses()[0]));
        System.out.println();

        session.connect();

        if (targetPeerId != null) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!session.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            String text = messageText != null ? messageText : "Hello -- this is an A-1 persistent-relay test message.";
            boolean sent = session.send(targetPeerId, text.getBytes(StandardCharsets.UTF_8));
            log(sent ? "sent to " + targetPeerId + ": \"" + text + "\"" : "send FAILED -- not currently connected");
        }

        System.out.println("Holding the relay connection open. Press Ctrl+C to stop.");
        System.out.println("Try leaving this running for a while idle, then restarting the relay server, and watch");
        System.out.println("for a 'disconnected' line followed by reconnection with no action needed here.");

        Thread.currentThread().join();
    }

    private static void log(String message) {
        System.out.println("[" + Instant.now() + "] [relay-session] " + message);
        System.out.flush();
    }

    private static String extractPeerId(String multiaddr) {
        int index = multiaddr.lastIndexOf("/p2p/");
        if (index == -1) {
            throw new IllegalArgumentException("Address does not contain a /p2p/<peer-id> component: " + multiaddr);
        }
        return multiaddr.substring(index + "/p2p/".length());
    }
}
