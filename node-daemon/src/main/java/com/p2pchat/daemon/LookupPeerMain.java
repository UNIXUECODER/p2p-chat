package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.DiscoveryLookupResult;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.model.PeerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class LookupPeerMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ./gradlew :node-daemon:runLookupPeer \\");
            System.out.println("           -Pdiscovery=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\" \\");
            System.out.println("           -Ptarget=\"<libp2p peer ID to look up>\"");
            return;
        }
        String discoveryAddress = args[0];
        String targetPeerId = args[1];

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { }, new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }
            @Override public void onFrame(PeerId sender, RelayFrame frame) { }
        }, new DiscoveryRequestHandler() {
            @Override public void onPublish(PeerId publisher, byte[] payload) { }
            @Override public byte[] onLookup(String requestedPeerId) { return null; }
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Looking up " + targetPeerId + " via discovery server at " + discoveryAddress + "...");

        DiscoveryController discovery = network.connectToDiscovery(discoveryAddress);
        DiscoveryLookupResult result = discovery.lookup(targetPeerId).get();

        if (result.found()) {
            String addresses = new String(result.payload(), StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("Found. Their published address(es):");
            for (String addr : addresses.split("\n")) {
                System.out.println("  " + addr);
            }
            System.out.println();
            System.out.println("This is what discovery replaces — no more hand-carrying that string between terminals.");
        } else {
            System.out.println();
            System.out.println("Not found — they haven't published a record with this discovery server (or it hasn't propagated yet).");
        }

        network.stop();
    }
}
