package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class NetworkPingerMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ./gradlew :node-daemon:runPinger -Paddr=\"/ip4/<peer-ip>/tcp/<port>/p2p/<peer-id>\"");
            return;
        }

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        PeerNetworkService network = new Libp2pNetworkService();
        // No incoming envelope traffic expected on this side for this demo — no-op callback.
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Data dir     : " + baseDir);
        System.out.println();

        String targetAddress = args[0];
        System.out.println("Pinging " + targetAddress + " ...");
        for (int i = 1; i <= 5; i++) {
            long latencyMs = network.pingPeer(targetAddress);
            System.out.println("Ping " + i + ": " + latencyMs + "ms");
        }

        System.out.println();
        String testMessage = "Hello from the pinger — M2b envelope test.";
        System.out.println("Sending an envelope message: \"" + testMessage + "\"");
        network.sendEnvelope(targetAddress, testMessage.getBytes(StandardCharsets.UTF_8));
        System.out.println("Sent. Check the listener's console for a matching [envelope] received line.");

        // Small pause before tearing down the connection — writeAndFlush() queues the
        // write, but stopping the host immediately after could close the socket before
        // those bytes actually reach the wire. Not needed once M2c has proper delivery
        // acknowledgement; needed for this one-shot demo.
        Thread.sleep(500);

        network.stop();
        System.out.println();
        System.out.println("Done. Ping proves M1. A stable listener peer ID across restarts proves M1.5.");
        System.out.println("The listener printing the envelope message above proves M2b.");
    }
}
