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
}
