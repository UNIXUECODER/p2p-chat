package com.p2pchat.daemon;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityNotFoundException;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);

        Identity identity;
        if (identityService.hasIdentity()) {
            try {
                identity = identityService.loadIdentity();
                System.out.println("Loaded existing identity.");
            } catch (IdentityNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else {
            String displayName = args.length > 0 ? args[0] : "anonymous";
            identity = identityService.createIdentity(displayName);
            System.out.println("Created new identity.");
        }

        System.out.println("Peer ID     : " + identity.peerId());
        System.out.println("Display name: " + identity.displayName());
        System.out.println("Created at  : " + identity.createdAt());
        System.out.println();
        System.out.println("Stored in: " + baseDir);
    }
}
