package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.model.PeerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * M3c: a normal direct listener (like NetworkListenerMain) that additionally
 * publishes its own address(es) to a discovery server, so LookupPeerMain can
 * find it without the address being hand-carried between terminals.
 */
public class PublishRecordMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ./gradlew :node-daemon:runPublishRecord -Pdiscovery=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\" [-Pport=9000]");
            return;
        }
        String discoveryAddress = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            String text = new String(data, StandardCharsets.UTF_8);
            System.out.println("[envelope] received from " + sender + ": \"" + text + "\"");
        }, new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }
            @Override public void onFrame(PeerId sender, RelayFrame frame) { }
        }, new DiscoveryRequestHandler() {
            @Override public void onPublish(PeerId publisher, byte[] payload) { }
            @Override public byte[] onLookup(String targetPeerId) { return null; }
        });

        String[] myAddresses = network.listenAddresses();
        String recordPayload = String.join("\n", myAddresses);

        DiscoveryController discovery = network.connectToDiscovery(discoveryAddress);
        discovery.publish(recordPayload.getBytes(StandardCharsets.UTF_8));

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Published my address(es) to the discovery server at " + discoveryAddress + ":");
        for (String addr : myAddresses) {
            System.out.println("  " + addr);
        }
        System.out.println();
        System.out.println("Also listening directly. Waiting for a ping and/or envelope message. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }
}
