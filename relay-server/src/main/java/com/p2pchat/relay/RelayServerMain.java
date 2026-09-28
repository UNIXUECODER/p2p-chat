package com.p2pchat.relay;

import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.storage.MigrationRunner;
import com.p2pchat.storage.SqliteDatabase;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A standalone, deployable relay node — deliberately its own module and its
 * own process, so anyone can run one (per docs/architecture-spec.md's "no
 * central authority" principle). Uses core-identity for a stable identity
 * the same way node-daemon does, so its address doesn't change across restarts.
 *
 * <p><b>Track A-3, Commit 4:</b> now owns the durable spool's lifecycle too — its own SQLite
 * file ({@code relay-spool.sqlite}, in the same data directory this process already uses for its
 * identity), its own independent migration set, an immediate cold-startup purge sweep for stale
 * backlog lingering across server restarts, and an hourly periodic TTL sweep (Commit 3's own
 * suggested cadence — see {@link SqliteRelaySpool#startPeriodicPurge} for why that's a real interval
 * here and not a test's tighter one). Quota/TTL/frame-size are left at {@link SqliteRelaySpool}'s
 * defaults deliberately — making these configurable per-deployment is Track A-4's job ("Relay
 * configuration & selection"), not this commit's.
 */
public class RelayServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-relay-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("relay");

        MigrationRunner relayMigrations = new MigrationRunner("/db/relay-migration/", List.of("V001__relay_spool.sql"));
        SqliteDatabase spoolDatabase = SqliteDatabase.openOrCreate(baseDir, "relay-spool.sqlite", relayMigrations);
        SqliteRelaySpool spool = new SqliteRelaySpool(spoolDatabase.connection());
        spool.purgeExpired(Instant.now()).thenAccept(purged -> {
            if (purged > 0) {
                System.out.println("[relay] startup sweep purged " + purged + " expired spooled message(s)");
            }
        });
        spool.startPeriodicPurge(Duration.ofHours(1));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            spool.close();
            try {
                spoolDatabase.close();
            } catch (SQLException e) {
                System.err.println("[relay] failed to close spool database: " + e);
            }
        }));

        RelayRegistry relayRegistry = new RelayRegistry(spool);
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
