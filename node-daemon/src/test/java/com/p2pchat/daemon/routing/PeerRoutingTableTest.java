package com.p2pchat.daemon.routing;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.model.PeerRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thin on top of StorageService's own merge-upsert semantics (already covered thoroughly by
 * SqliteStorageServiceTest's peer_routes tests) — this just proves PeerRoutingTable's three
 * methods genuinely delegate rather than silently diverging from what StorageService does.
 */
class PeerRoutingTableTest {

    private SqliteDatabase database;
    private PeerRoutingTable routingTable;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        routingTable = new PeerRoutingTable(new SqliteStorageService(database));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void getReturnsNullBeforeAnyObservation() {
        assertThat(routingTable.get(new PeerId("peer-nobody"))).isNull();
    }

    @Test
    void upsertReturnsTheMergedRowAndGetSeesTheSameState() {
        PeerId alice = new PeerId("peer-alice");
        routingTable.upsert(new PeerRoute(alice, "/ip4/10.0.0.1/tcp/9000", null, null, null, 1000L));
        PeerRoute merged = routingTable.upsert(new PeerRoute(alice, null, "/ip4/1.2.3.4/tcp/4001", "Alice", null, 2000L));

        assertThat(merged.directMultiaddr()).isEqualTo("/ip4/10.0.0.1/tcp/9000"); // preserved, not erased
        assertThat(merged.relayMultiaddr()).isEqualTo("/ip4/1.2.3.4/tcp/4001");
        assertThat(routingTable.get(alice)).isEqualTo(merged);
    }

    @Test
    void listReturnsEveryKnownRoute() {
        routingTable.upsert(new PeerRoute(new PeerId("peer-a"), "/ip4/10.0.0.1/tcp/9000", null, null, null, 100L));
        routingTable.upsert(new PeerRoute(new PeerId("peer-b"), "/ip4/10.0.0.2/tcp/9000", null, null, null, 200L));

        List<PeerRoute> routes = routingTable.list();
        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(PeerRoute::peerId)
                .containsExactlyInAnyOrder(new PeerId("peer-a"), new PeerId("peer-b"));
    }

    @Test
    void routesSurviveRestartAcrossDatabaseReopen(@TempDir Path tempDir) throws SQLException {
        PeerId peerId = new PeerId("peer-restart-123");
        byte[] preKeyBundle = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        PeerRoute initial = new PeerRoute(
                peerId,
                "/ip4/192.168.1.50/tcp/9000",
                "/ip4/10.0.0.1/tcp/4001/p2p/12D3KooWRelay",
                "Alice",
                preKeyBundle,
                1700000000L
        );

        // Open first database instance, persist the route, then close it cleanly (simulating daemon shutdown)
        try (SqliteDatabase db1 = SqliteDatabase.openOrCreate(tempDir)) {
            PeerRoutingTable table1 = new PeerRoutingTable(new SqliteStorageService(db1));
            table1.upsert(initial);
            assertThat(table1.get(peerId)).isEqualTo(initial);
        }

        // Open a brand-new database instance against the exact same directory (simulating daemon restart)
        try (SqliteDatabase db2 = SqliteDatabase.openOrCreate(tempDir)) {
            PeerRoutingTable table2 = new PeerRoutingTable(new SqliteStorageService(db2));
            PeerRoute restored = table2.get(peerId);

            assertThat(restored).isNotNull();
            assertThat(restored.peerId()).isEqualTo(peerId);
            assertThat(restored.directMultiaddr()).isEqualTo("/ip4/192.168.1.50/tcp/9000");
            assertThat(restored.relayMultiaddr()).isEqualTo("/ip4/10.0.0.1/tcp/4001/p2p/12D3KooWRelay");
            assertThat(restored.displayName()).isEqualTo("Alice");
            assertThat(restored.preKeyBundle()).isEqualTo(preKeyBundle);
            assertThat(restored.lastSeen()).isEqualTo(1700000000L);

            List<PeerRoute> routes = table2.list();
            assertThat(routes).hasSize(1);
            assertThat(routes.get(0)).isEqualTo(restored);
        }
    }
}
