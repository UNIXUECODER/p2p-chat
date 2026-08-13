package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.ConnectionStrategy;
import com.p2pchat.network.ConnectivityStatus;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.model.PeerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * M3b demo. Deliberately reuses existing, already-verified listeners rather
 * than needing a new dual-capable one:
 *   - To prove the DIRECT path: point -Pdirectaddr at a running
 *     NetworkListenerMain (M1) and watch its console for [envelope] received.
 *   - To prove the RELAY fallback: point -Pdirectaddr at a closed port (e.g.
 *     a bad local port — connection refused is near-instant on loopback) or
 *     omit it, and point -Prelay/-Ptarget at a running RelayRegisterMain
 *     (M3a), watching its console/log for [relay] delivered.
 */
public class ReachPeerMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: ./gradlew :node-daemon:runReachPeer \\");
            System.out.println("           -Pdirectaddr=\"<direct multiaddr, blank to skip straight to relay>\" \\");
            System.out.println("           -Prelay=\"<relay multiaddr>\" \\");
            System.out.println("           -Ptarget=\"<target's libp2p peer ID, for the relay fallback>\" \\");
            System.out.println("           -Pmessage=\"your message\"");
            return;
        }
        String directAddr = args[0].isBlank() ? null : args[0];
        String relayAddr = args[1];
        String targetPeerId = args[2];
        String messageText = args[3];

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { }, new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }
            @Override public void onFrame(PeerId sender, RelayFrame frame) { }
        });

        ConnectionStrategy strategy = new ConnectionStrategy(network, 3000);

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Direct address: " + (directAddr != null ? directAddr : "(none given — going straight to relay)"));
        System.out.println("Attempting direct first (3s timeout), falling back to relay if needed...");

        long startedAt = System.currentTimeMillis();
        ConnectivityStatus status = strategy.send(
                directAddr, relayAddr, targetPeerId, messageText.getBytes(StandardCharsets.UTF_8));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        System.out.println();
        System.out.println("Result: " + status + " (took " + elapsedMs + "ms)");
        switch (status) {
            case DIRECT -> System.out.println("Reached the peer directly — check its console for [envelope] received.");
            case RELAYED -> System.out.println("Direct didn't work; fell back to the relay — check the registered peer's console/log for [relay] delivered.");
            case UNREACHABLE -> System.out.println("Neither direct nor relay worked — check the addresses given.");
        }

        // Same reasoning as every prior sender demo: give the write time to actually
        // reach the wire before tearing the connection down.
        Thread.sleep(500);
        network.stop();
    }
}
