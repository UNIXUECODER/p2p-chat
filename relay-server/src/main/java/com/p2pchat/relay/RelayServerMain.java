package com.p2pchat.relay;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;

import java.nio.file.Path;

/**
 * A standalone, deployable relay node — deliberately its own module and its
 * own process, so anyone can run one (per docs/architecture-spec.md's "no
 * central authority" principle). Uses core-identity for a stable identity
 * the same way node-daemon does, so its address doesn't change across restarts.
 */
public class RelayServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-relay-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("relay");

        RelayRegistry relayRegistry = new RelayRegistry();
        DiscoveryRegistry discoveryRegistry = new DiscoveryRegistry();

        PeerNetworkService network = new Libp2pNetworkService();
        // Envelope traffic isn't meaningful for a relay itself — no-op callback.
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> { }, relayRegistry, discoveryRegistry);

        System.out.println("Relay/discovery identity : " + identity.peerId());
        System.out.println("Data dir                 : " + baseDir);
        System.out.println();
        System.out.println("Give this address to any peer that wants to use this relay or its discovery service:");
        for (String addr : network.listenAddresses()) {
            System.out.println("  " + addr);
        }
        System.out.println();
        System.out.println("Waiting for peers to connect. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }
}
