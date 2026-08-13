package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.model.PeerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class RelayForwardMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runRelayForward \\");
            System.out.println("           -Prelay=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\" \\");
            System.out.println("           -Ptarget=\"<libp2p peer ID printed by runRelayRegister, NOT the app identity>\" \\");
            System.out.println("           -Pmessage=\"your message here\"");
            return;
        }
        String relayAddress = args[0];
        String targetPeerId = args[1];
        String messageText = args[2];

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

        RelayController relay = network.connectToRelay(relayAddress, new RelayEventHandler() {
            @Override
            public void onConnected(PeerId peerId, RelayController controller) {
                System.out.println("Connected to relay " + peerId);
            }

            @Override
            public void onFrame(PeerId sender, RelayFrame frame) {
                System.out.println("[relay] unexpected frame received: " + frame);
            }
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        relay.send(new RelayFrame(true, targetPeerId, messageText.getBytes(StandardCharsets.UTF_8)));
        System.out.println("Asked the relay to forward to " + targetPeerId + ": \"" + messageText + "\"");

        Thread.sleep(500);
        network.stop();
        System.out.println();
        System.out.println("Done. If the registered peer printed a matching [relay] delivered line, M3a is proven —");
        System.out.println("a message reached a peer with NO direct connection between the two endpoints at all.");
    }
}
