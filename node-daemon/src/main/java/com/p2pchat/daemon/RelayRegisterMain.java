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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class RelayRegisterMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ./gradlew :node-daemon:runRelayRegister -Prelay=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\"");
            return;
        }
        String relayAddress = args[0];

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        Path deliveryLog = baseDir.resolve("relay-deliveries.log");
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        // One handler, used for both the host's own relay slot and the actual
        // connectToRelay() dial — consolidated from two separate anonymous
        // classes after testing showed the duplication was a real (if
        // unexercised) gap: a peer dialing this host directly via the Relay
        // protocol would previously have hit a no-op instead of this logic.
        RelayEventHandler handler = new RelayEventHandler() {
            @Override
            public void onConnected(PeerId peerId, RelayController controller) {
                System.out.println("Connected to relay " + peerId);
            }

            @Override
            public void onFrame(PeerId sender, RelayFrame frame) {
                if (!frame.isForwardRequest()) {
                    String text = new String(frame.payload(), StandardCharsets.UTF_8);
                    String line = "[relay] delivered from " + frame.peerId() + ": \"" + text + "\"";
                    System.out.println(line);
                    System.out.flush();
                    try {
                        Files.writeString(deliveryLog, "[" + Instant.now() + "] " + line + System.lineSeparator(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (Exception e) {
                        System.out.println("(failed to write delivery log: " + e + ")");
                    }
                }
            }
        };

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { }, handler);

        // This IS the meaningful connection: dialing the relay and keeping it open.
        network.connectToRelay(relayAddress, handler);

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Registered with relay at " + relayAddress);
        System.out.println();
        System.out.println("Give the sender THIS as -Ptarget (the relay keys peers by libp2p peer ID, not the app identity above):");
        System.out.println("  " + extractPeerId(network.listenAddresses()[0]));
        System.out.println();
        System.out.println("Log file path: " + deliveryLog);
        System.out.println("Waiting for a relayed message. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    private static String extractPeerId(String multiaddr) {
        int index = multiaddr.lastIndexOf("/p2p/");
        if (index == -1) {
            throw new IllegalArgumentException("Address does not contain a /p2p/<peer-id> component: " + multiaddr);
        }
        return multiaddr.substring(index + "/p2p/".length());
    }
}
