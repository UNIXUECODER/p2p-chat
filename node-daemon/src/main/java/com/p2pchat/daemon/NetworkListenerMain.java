package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class NetworkListenerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            // M2b: decode as UTF-8 text purely for this demo's own readability.
            // Real messages (M2c onward) will be PQXDH/Double-Ratchet ciphertext,
            // not printable text — this callback is just proving bytes arrive intact.
            String text = new String(data, StandardCharsets.UTF_8);
            System.out.println("[envelope] received from " + sender + ": \"" + text + "\"");
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Data dir     : " + baseDir);
        System.out.println();
        System.out.println("Listening. On the OTHER machine (same LAN), run runPinger with one of these addresses:");
        for (String addr : network.listenAddresses()) {
            System.out.println("  " + addr);
        }
        System.out.println();
        System.out.println("Restart this and compare — the /p2p/... suffix above should stay IDENTICAL every time.");
        System.out.println("Waiting for a ping and/or an envelope message. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }
}
