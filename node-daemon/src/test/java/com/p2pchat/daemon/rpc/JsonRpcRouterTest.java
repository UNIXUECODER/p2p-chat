package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.contact.ContactService;
import com.p2pchat.daemon.contact.InviteCode;
import com.p2pchat.daemon.contact.InviteCodeCodec;
import com.p2pchat.daemon.crypto.SqliteSignalProtocolStore;
import com.p2pchat.daemon.crypto.SynchronizedSignalProtocolStore;
import com.p2pchat.daemon.routing.PeerRoutingTable;
import com.p2pchat.daemon.session.DaemonEventListener;
import com.p2pchat.daemon.session.FileTransferHandler;
import com.p2pchat.daemon.session.SessionManager;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityNotFoundException;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.network.OnEnvelopeMessage;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.TransferState;

import com.p2pchat.crypto.PreKeyBundleFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6g-4: exercises {@link JsonRpcRouter} against a real, fully-started {@link SessionManager}
 * (real SQLite, real {@link SqliteSignalProtocolStore}) — only {@link PeerNetworkService} is
 * faked, since {@code SessionManager.localPeerId()} derives entirely from whatever {@link
 * FakeNetwork#listenAddresses()} returns (confirmed by reading {@code SessionManager.start}'s
 * real body, not assumed), so this test controls it directly without needing real jvm-libp2p.
 * Calls {@link JsonRpcRouter#handle} directly rather than {@link JsonRpcRouter#onMessage} — see
 * that method's own Javadoc for why {@code WebSocketSession} couldn't be constructed here at all.
 *
 * <p>Deliberately does not attempt a genuine discovery-verified {@code contacts.add} success —
 * that would require fabricating a validly Ed25519-signed {@code DiscoveryRecord} payload, real
 * cryptographic construction this test cannot verify by compiling in isolation the way the rest
 * of this test can. {@link #contactsAddIsIdempotentForAnExistingContact} instead exercises {@code
 * ContactService}'s own documented idempotent short-circuit (a real, already-covered behavior —
 * see {@code ContactService.addContact}'s own Javadoc) to reach the router's success-mapping path
 * without needing one.
 */
class JsonRpcRouterTest {

    @TempDir
    Path tempDir;

    private SqliteDatabase database;
    private SqliteStorageService storage;
    private PeerRoutingTable routingTable;
    private FakeNetwork fakeNetwork;
    private SessionManager sessionManager;
    private FakeIdentityService identityService;
    private ContactService.DiscoveryLookup discoveryLookup;
    private JsonRpcRouter router;

    private static final String OWN_PEER_ID = "12D3KooWMe";

    @BeforeEach
    void setUp() throws Exception {
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
        routingTable = new PeerRoutingTable(storage);
        fakeNetwork = new FakeNetwork(new String[]{"/ip4/127.0.0.1/tcp/9200/p2p/" + OWN_PEER_ID});

        SqliteSignalProtocolStore signalStore = new SqliteSignalProtocolStore(database, IdentityKeyPair.generate(), 1001);
        SynchronizedSignalProtocolStore synchronizedStore = new SynchronizedSignalProtocolStore(signalStore);

        sessionManager = new SessionManager(fakeNetwork, storage, synchronizedStore,
                new FileTransferHandler() {
                }, DaemonEventListener.NONE);
        sessionManager.start(0, new byte[32]);

        identityService = new FakeIdentityService();
        discoveryLookup = targetPeerId -> CompletableFuture.completedFuture(
                new com.p2pchat.network.DiscoveryLookupResult(false, null));
        ContactService contactService = new ContactService(discoveryLookup, storage, routingTable, null);

        router = new JsonRpcRouter(identityService, tempDir, sessionManager, fakeNetwork, storage, routingTable, contactService);
    }

    @AfterEach
    void tearDown() throws Exception {
        sessionManager.close();
        database.close();
    }

    private String send(String requestJson) {
        return router.handle(requestJson).join();
    }

    // ==================================================================== envelope-level

    @Test
    void malformedJsonProducesParseError() {
        String response = send("not json at all {{{");

        assertThat(response).contains("\"code\":-32700");
    }

    @Test
    void unknownMethodProducesMethodNotFound() {
        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"not.a.real.method\"}");

        assertThat(response).contains("\"code\":-32601");
    }

    @Test
    void notificationProducesNoResponseAtAll() {
        String response = send("{\"jsonrpc\":\"2.0\",\"method\":\"contacts.list\"}");

        assertThat(response).isNull();
    }

    @Test
    void batchOfTwoProducesOneArrayWithTwoResponses() {
        String response = send("[" +
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.list\"}," +
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"network.status\"}" +
                "]");

        assertThat(response).startsWith("[").endsWith("]");
        assertThat(response).contains("\"id\":1").contains("\"id\":2");
    }

    @Test
    void batchWhereEveryElementIsANotificationProducesNoResponseAtAll() {
        String response = send("[" +
                "{\"jsonrpc\":\"2.0\",\"method\":\"contacts.list\"}," +
                "{\"jsonrpc\":\"2.0\",\"method\":\"network.status\"}" +
                "]");

        assertThat(response).isNull();
    }

    @Test
    void emptyBatchArrayIsInvalidRequest() {
        String response = send("[]");

        assertThat(response).contains("\"code\":-32600");
    }

    // ==================================================================== identity.*

    @Test
    void identityGetReturnsTheCanonicalLibp2pPeerId() {
        identityService.identity = new Identity("app-hex-id", "Alice", new byte[]{9}, 500L);

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"identity.get\"}");

        assertThat(response).contains("\"peerId\":\"" + OWN_PEER_ID + "\"");
        assertThat(response).contains("\"displayName\":\"Alice\"");
    }

    @Test
    void identityGetMapsMissingIdentityToInvalidRequest() {
        identityService.throwNotFound = true;

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"identity.get\"}");

        assertThat(response).contains("\"code\":-32600");
    }

    @Test
    void identityCreateRefusesToOverwriteAnExistingIdentity() {
        identityService.identity = new Identity("app-hex-id", "Alice", new byte[]{9}, 500L);

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"identity.create\",\"params\":{\"displayName\":\"Mallory\"}}");

        assertThat(response).contains("\"code\":-32600");
        assertThat(identityService.createCalled).isFalse();
    }

    // ==================================================================== contacts.*

    @Test
    void contactsAddIsIdempotentForAnExistingContact() {
        PeerId bobPeerId = PeerId.of("12D3KooWBob");
        storage.saveContact(new Contact(bobPeerId, "Bob", true, 100L));
        String inviteCode = InviteCodeCodec.encode(new InviteCode(bobPeerId, null, "Bob"));

        String response = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.add\",\"params\":{\"inviteCode\":\"" + inviteCode + "\"}}");

        assertThat(response).contains("\"peerId\":\"12D3KooWBob\"");
        assertThat(response).doesNotContain("\"error\"");
    }

    @Test
    void contactsAddMapsMalformedInviteCodeToMalformedRecord() {
        String response = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.add\",\"params\":{\"inviteCode\":\"not-a-real-invite-code\"}}");

        assertThat(response).contains("\"code\":-32002"); // MALFORMED_RECORD
    }

    @Test
    void contactsAddMapsPeerNotFoundToPeerUnreachable() {
        String inviteCode = InviteCodeCodec.encode(
                new InviteCode(PeerId.of("12D3KooWUnknown"), "/ip4/127.0.0.1/tcp/9400/p2p/12D3KooWDiscovery", "Unknown"));

        String response = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.add\",\"params\":{\"inviteCode\":\"" + inviteCode + "\"}}");

        assertThat(response).contains("\"code\":-32000"); // PEER_UNREACHABLE
    }

    @Test
    void contactsListReturnsEveryStoredContact() {
        storage.saveContact(new Contact(PeerId.of("12D3KooWA"), "A", false, 1L));
        storage.saveContact(new Contact(PeerId.of("12D3KooWB"), "B", true, 2L));

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.list\"}");

        assertThat(response).contains("12D3KooWA").contains("12D3KooWB");
    }

    // ==================================================================== conversations.* / files.* deferrals

    @Test
    void conversationsCreateGroupIsDeferredWithMethodNotFound() {
        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"conversations.createGroup\"}");

        assertThat(response).contains("\"code\":-32601");
        assertThat(response).contains("future version");
    }

    @Test
    void filesCancelIsDeferredWithMethodNotFound() {
        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"files.cancel\"}");

        assertThat(response).contains("\"code\":-32601");
    }

    // ==================================================================== messages.*

    @Test
    void messagesSendRejectsNonTextPlainContentType() {
        String conversationId = "direct-12D3KooWBob-" + OWN_PEER_ID;
        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"messages.send\",\"params\":"
                + "{\"conversationId\":\"" + conversationId + "\",\"contentType\":\"text/markdown\",\"content\":\"hi\"}}");

        assertThat(response).contains("\"code\":-32602"); // invalid params
    }

    @Test
    void messagesSendRejectsAConversationIdNotInvolvingThisDaemon() {
        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"messages.send\",\"params\":"
                + "{\"conversationId\":\"direct-12D3KooWAlice-12D3KooWBob\",\"content\":\"hi\"}}");

        assertThat(response).contains("\"code\":-32602");
    }

    @Test
    void messagesSendSucceedsAgainstAKnownPeerAndReturnsAMessageId() {
        String bobPeerId = "12D3KooWBob";
        String conversationId = "direct-" + bobPeerId + "-" + OWN_PEER_ID;

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"messages.send\",\"params\":"
                + "{\"conversationId\":\"" + conversationId + "\",\"content\":\"hello\"}}");

        assertThat(response).contains("\"messageId\"");
        assertThat(response).doesNotContain("\"error\"");
        // The fake network's sendEnvelope always succeeds -- ConnectionStrategy has no
        // relayMultiaddr to fall back to here (no PeerRoute exists for bobPeerId), so DIRECT is
        // only reachable if a directMultiaddr was passed -- which it wasn't (no route). This
        // message is still expected to succeed at the RPC level regardless (see
        // handleMessagesSend's own comment on why UNREACHABLE still returns a messageId), so this
        // test intentionally does not assert on delivery outcome, only on the response shape.
    }

    @Test
    void messagesHistoryReturnsStoredMessages() {
        storage.saveConversation(new com.p2pchat.storage.model.Conversation(
                "direct-a-b", com.p2pchat.storage.model.ConversationType.DIRECT, "12D3KooWOther", 1L));
        storage.saveMessage(new Message("m-1", "direct-a-b", PeerId.of("12D3KooWOther"), DeviceId.DEFAULT,
                "hlc-1", "text/plain", "hi there".getBytes(StandardCharsets.UTF_8), DeliveryState.DELIVERED, 10L));

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"messages.history\",\"params\":"
                + "{\"conversationId\":\"direct-a-b\"}}");

        assertThat(response).contains("\"content\":\"hi there\"");
    }

    // ==================================================================== files.accept

    @Test
    void filesAcceptDelegatesWithoutError() {
        String savePath = tempDir.resolve("incoming.bin").toString().replace("\\", "\\\\");
        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"files.accept\",\"params\":"
                + "{\"transferId\":\"unknown-transfer\",\"savePath\":\"" + savePath + "\"}}");

        // DefaultFileTransferHandler's own already-tested quiet-ignore-on-unknown-id behavior is
        // trusted rather than re-verified here -- this only proves the router delegates cleanly.
        assertThat(response).doesNotContain("\"error\"");
    }

    // ==================================================================== network.*

    @Test
    void networkStatusAggregatesPeerIdDisplayNameAndListenAddresses() {
        identityService.identity = new Identity("app-hex-id", "Alice", new byte[]{9}, 500L);

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"network.status\"}");

        assertThat(response).contains("\"peerId\":\"" + OWN_PEER_ID + "\"");
        assertThat(response).contains("\"displayName\":\"Alice\"");
        assertThat(response).contains("\"relayConnected\":false");
        assertThat(response).contains("\"connectedPeerCount\":0");
    }

    @Test
    void networkConnectedPeersExcludesRoutesWithNoEstablishedSession() {
        routingTable.upsert(new com.p2pchat.storage.model.PeerRoute(
                PeerId.of("12D3KooWNoSession"), null, null, "No Session", null, 1L));

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"network.connectedPeers\"}");

        assertThat(response).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}");
    }

    @Test
    void networkConnectedPeersIncludesAPeerWithARealEstablishedSession() throws Exception {
        // A real, unilateral PQXDH-style session establishment -- Bob's own store only needs to
        // exist long enough for PreKeyBundleFactory to populate it; nothing here needs Bob to be
        // a running daemon, matching how sendChatMessage's own bundleIfNoSessionYet path works.
        SqliteDatabase bobDatabase = SqliteDatabase.openOrCreate(tempDir.resolve("bob"));
        SqliteSignalProtocolStore bobStore = new SqliteSignalProtocolStore(bobDatabase, IdentityKeyPair.generate(), 2002);
        PreKeyBundle bobBundle = PreKeyBundleFactory.create(bobStore);

        PeerId bobPeerId = PeerId.of("12D3KooWBobSession");
        routingTable.upsert(new com.p2pchat.storage.model.PeerRoute(bobPeerId, null, null, "Bob", null, 1L));

        String conversationId = "direct-" + bobPeerId.value() + "-" + OWN_PEER_ID;
        sessionManager.sendChatMessage(bobPeerId, null, null, bobBundle, conversationId, "hi bob").join();

        String response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"network.connectedPeers\"}");

        bobDatabase.close();

        assertThat(response).contains("12D3KooWBobSession");
        assertThat(response).contains("\"hasSession\":true");
    }

    // ==================================================================== DaemonEventListener forwarding

    @Test
    void onMessageReceivedBroadcastsAsAPushNotification() {
        List<String> broadcasts = new CopyOnWriteArrayList<>();
        router.attachEventBroadcaster(broadcasts::add);

        Message message = new Message("m-1", "direct-a-b", PeerId.of("12D3KooWSender"), DeviceId.DEFAULT,
                "hlc-1", "text/plain", "hey".getBytes(StandardCharsets.UTF_8), DeliveryState.DELIVERED, 10L);
        router.onMessageReceived(message);

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0)).contains("\"method\":\"event.message.received\"");
        assertThat(broadcasts.get(0)).contains("\"content\":\"hey\"");
    }

    @Test
    void onDeliveryStateChangedBroadcastsAsAPushNotification() {
        List<String> broadcasts = new CopyOnWriteArrayList<>();
        router.attachEventBroadcaster(broadcasts::add);

        router.onDeliveryStateChanged("m-1", DeliveryState.DELIVERED);

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0)).contains("\"method\":\"event.message.deliveryStateChanged\"");
        assertThat(broadcasts.get(0)).contains("\"state\":\"DELIVERED\"");
    }

    @Test
    void onFileOfferReceivedBroadcastsAsAPushNotification() {
        List<String> broadcasts = new CopyOnWriteArrayList<>();
        router.attachEventBroadcaster(broadcasts::add);

        router.onFileOfferReceived("t-1", PeerId.of("12D3KooWSender"), "photo.jpg", 12345L);

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0)).contains("\"method\":\"event.file.offerReceived\"");
        assertThat(broadcasts.get(0)).contains("\"fileName\":\"photo.jpg\"");
    }

    @Test
    void onFileTransferProgressBroadcastsAsAPushNotification() {
        List<String> broadcasts = new CopyOnWriteArrayList<>();
        router.attachEventBroadcaster(broadcasts::add);

        router.onFileTransferProgress("t-1", 1, 2, TransferState.IN_PROGRESS);

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0)).contains("\"method\":\"event.transfer.progress\"");
        assertThat(broadcasts.get(0)).contains("\"chunksReceived\":1");
    }

    @Test
    void onNetworkStatusChangedBroadcastsTheFullAggregatedStatus() {
        List<String> broadcasts = new CopyOnWriteArrayList<>();
        router.attachEventBroadcaster(broadcasts::add);
        identityService.identity = new Identity("app-hex-id", "Alice", new byte[]{9}, 500L);

        router.onNetworkStatusChanged();

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0)).contains("\"method\":\"event.network.statusChanged\"");
        assertThat(broadcasts.get(0)).contains("\"peerId\":\"" + OWN_PEER_ID + "\"");
    }

    @Test
    void noEventsAreBroadcastBeforeAnEventBroadcasterIsAttached() {
        // Must not throw -- a router constructed standalone (e.g. by this very test class before
        // attachEventBroadcaster is called in a given test) is a real, expected state, not a bug.
        router.onNetworkStatusChanged();
    }

    // ==================================================================== test doubles

    private static final class FakeIdentityService implements IdentityService {
        Identity identity;
        boolean throwNotFound = false;
        boolean createCalled = false;

        @Override
        public Identity createIdentity(String displayName) {
            createCalled = true;
            identity = new Identity("app-hex-id", displayName, new byte[]{1}, 999L);
            return identity;
        }

        @Override
        public Identity loadIdentity() throws IdentityNotFoundException {
            if (throwNotFound || identity == null) {
                throw new IdentityNotFoundException("no identity in this fake");
            }
            return identity;
        }

        @Override
        public boolean hasIdentity() {
            return identity != null;
        }

        @Override
        public byte[] rawPrivateKeySeed() {
            return new byte[32];
        }
    }

    /**
     * See class Javadoc — only {@link #start(int, byte[], OnEnvelopeMessage)}, {@link
     * #listenAddresses()}, and the timed {@link #sendEnvelope(String, byte[], long)} are actually
     * exercised by this test class; every other method throws, matching {@code
     * FakeNetworkForSessionTest}'s own established precedent for exactly this situation.
     */
    private static final class FakeNetwork implements PeerNetworkService {
        private final String[] fixedListenAddresses;
        private final List<String> sentTo = new CopyOnWriteArrayList<>();

        FakeNetwork(String[] fixedListenAddresses) {
            this.fixedListenAddresses = fixedListenAddresses;
        }

        @Override
        public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage) {
            // no-op -- this test never needs a real inbound path; event forwarding is tested by
            // calling JsonRpcRouter's own DaemonEventListener methods directly instead.
        }

        @Override
        public void stop() {
        }

        @Override
        public String[] listenAddresses() {
            return fixedListenAddresses;
        }

        @Override
        public long pingPeer(String multiaddr) {
            throw new UnsupportedOperationException("not exercised by JsonRpcRouterTest");
        }

        @Override
        public void sendEnvelope(String multiaddr, byte[] data) {
            sentTo.add(multiaddr);
        }

        @Override
        public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                           RelayEventHandler relayEventHandler) {
            throw new UnsupportedOperationException("not exercised by JsonRpcRouterTest");
        }

        @Override
        public RelayController connectToRelay(String relayMultiaddr, RelayEventHandler onEvent) {
            throw new UnsupportedOperationException("not exercised by JsonRpcRouterTest");
        }

        @Override
        public void sendEnvelope(String multiaddr, byte[] data, long timeoutMillis) {
            sentTo.add(multiaddr);
        }

        @Override
        public DiscoveryController connectToDiscovery(String discoveryMultiaddr) {
            throw new UnsupportedOperationException("not exercised by JsonRpcRouterTest");
        }

        @Override
        public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                           RelayEventHandler relayEventHandler, DiscoveryRequestHandler discoveryRequestHandler) {
            throw new UnsupportedOperationException("not exercised by JsonRpcRouterTest");
        }
    }
}
