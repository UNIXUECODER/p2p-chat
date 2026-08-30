package com.p2pchat.storage;

import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.ConversationType;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.Pagination;
import com.p2pchat.storage.model.PeerRoute;
import com.p2pchat.storage.model.TransferState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteStorageServiceTest {

    private SqliteDatabase database;
    private SqliteStorageService storageService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        storageService = new SqliteStorageService(database);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void saveAndQueryContacts() {
        Contact contact = new Contact(new PeerId("peer-123"), "Bob", true, System.currentTimeMillis());
        storageService.saveContact(contact);
    }

    @Test
    void saveAndQueryMessages() {
        storageService.saveConversation(new Conversation("c1", ConversationType.DIRECT, "Bob", 1000));

        Message msg1 = new Message("m1", "c1", new PeerId("p1"), new DeviceId("0"), "1000-1", "text/plain", "Hello".getBytes(), DeliveryState.SENT, System.currentTimeMillis());
        Message msg2 = new Message("m2", "c1", new PeerId("p1"), new DeviceId("0"), "1000-2", "text/plain", "World".getBytes(), DeliveryState.SENT, System.currentTimeMillis());

        storageService.saveMessage(msg1);
        storageService.saveMessage(msg2);

        List<Message> messages = storageService.queryMessages("c1", new Pagination(null, 10));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).messageId()).isEqualTo("m1");
        assertThat(new String(messages.get(0).plaintext())).isEqualTo("Hello");
        assertThat(messages.get(1).messageId()).isEqualTo("m2");
        assertThat(new String(messages.get(1).plaintext())).isEqualTo("World");
    }

    @Test
    void saveFileMetadata() {
        FileTransfer transfer = new FileTransfer(
                "t1",
                "c1",
                "file.txt",
                1024L,
                "hash123",
                256 * 1024,
                4,
                TransferState.OFFERED,
                "/tmp/file.txt",
                System.currentTimeMillis()
        );
        storageService.saveFileMetadata(transfer);
    }

    @Test
    void updateTransferStateChangesAnExistingTransferStateAndUnknownIsANoOp() throws SQLException {
        FileTransfer transfer = new FileTransfer(
                "t1", "c1", "file.txt", 1024L, "hash123", 256 * 1024, 4,
                TransferState.OFFERED, "/tmp/file.txt", System.currentTimeMillis()
        );
        storageService.saveFileMetadata(transfer);

        storageService.updateTransferState("t1", TransferState.IN_PROGRESS);

        try (var statement = database.connection().prepareStatement("SELECT state FROM file_transfers WHERE transfer_id = ?")) {
            statement.setString(1, "t1");
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("state")).isEqualTo("IN_PROGRESS");
            }
        }

        // Unknown transferId is a no-op, must not throw
        storageService.updateTransferState("no-such-transfer", TransferState.COMPLETED);
    }

    @Test
    void transactionRollbackOnException() {
        storageService.saveConversation(new Conversation("c1", ConversationType.DIRECT, "Bob", 1000));

        try {
            storageService.runInTransaction(() -> {
                storageService.saveMessage(new Message("m100", "c1", new PeerId("p1"), new DeviceId("0"), "2000-1", "text/plain", "Tx test".getBytes(), DeliveryState.SENT, System.currentTimeMillis()));
                throw new RuntimeException("Simulated failure");
            });
        } catch (RuntimeException ignored) {
        }

        List<Message> messages = storageService.queryMessages("c1", new Pagination(null, 10));
        assertThat(messages).isEmpty();
    }

    // --- M4e regression coverage: the messages -> conversations foreign key -----------------
    //
    // Before M4e, StorageService had no method that could create a `conversations` row at all,
    // so saveMessage() always failed with a foreign-key violation unless the caller reached
    // around StorageService and inserted the row with raw JDBC — which is exactly what this
    // test class and StorageDemoMain both used to do, silently masking the gap. These two tests
    // pin down the actual bug and its fix directly, instead of relying on every other test
    // happening to call saveConversation() first.

    @Test
    void saveMessageFailsWithoutAConversation() {
        // No saveConversation() call for "no-such-conversation" — this must fail closed with
        // the real foreign-key violation, not silently succeed or synthesize a row.
        Message orphan = new Message("m1", "no-such-conversation", new PeerId("p1"), new DeviceId("0"),
                "1000-1", "text/plain", "Hello".getBytes(), DeliveryState.SENT, System.currentTimeMillis());

        assertThatThrownBy(() -> storageService.saveMessage(orphan))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("FOREIGN KEY");
    }

    @Test
    void saveConversationIsIdempotentAndUnblocksSaveMessage() {
        Conversation conversation = new Conversation("c1", ConversationType.DIRECT, "Bob", 1000);

        // Calling this twice for the same conversationId — e.g. once per message sent to the
        // same 1:1 contact — must not throw. See saveConversation's Javadoc for why this is an
        // upsert (INSERT OR IGNORE), not a plain insert.
        storageService.saveConversation(conversation);
        storageService.saveConversation(conversation);

        Message message = new Message("m1", "c1", new PeerId("p1"), new DeviceId("0"),
                "1000-1", "text/plain", "Hello".getBytes(), DeliveryState.SENT, System.currentTimeMillis());
        storageService.saveMessage(message);

        List<Message> messages = storageService.queryMessages("c1", new Pagination(null, 10));
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).messageId()).isEqualTo("m1");
    }

    // --- M5d: message dedup + delivery/read receipt state transitions -----------------------

    @Test
    void hasMessageReflectsWhetherAMessageIdHasBeenSaved() {
        storageService.saveConversation(new Conversation("c1", ConversationType.DIRECT, "Bob", 1000));

        assertThat(storageService.hasMessage("m1")).isFalse();

        storageService.saveMessage(new Message("m1", "c1", new PeerId("p1"), new DeviceId("0"),
                "1000-1", "text/plain", "Hello".getBytes(), DeliveryState.SENT, System.currentTimeMillis()));

        assertThat(storageService.hasMessage("m1")).isTrue();
        assertThat(storageService.hasMessage("m-does-not-exist")).isFalse();
    }

    @Test
    void updateDeliveryStateChangesAnExistingMessagesState() {
        storageService.saveConversation(new Conversation("c1", ConversationType.DIRECT, "Bob", 1000));
        storageService.saveMessage(new Message("m1", "c1", new PeerId("p1"), new DeviceId("0"),
                "1000-1", "text/plain", "Hello".getBytes(), DeliveryState.SENT, System.currentTimeMillis()));

        storageService.updateDeliveryState("m1", DeliveryState.DELIVERED);

        Message updated = storageService.queryMessages("c1", new Pagination(null, 10)).get(0);
        assertThat(updated.state()).isEqualTo(DeliveryState.DELIVERED);
    }

    @Test
    void updateDeliveryStateOnUnknownMessageIdIsANoOp() {
        // Must not throw — a receipt racing ahead of a local dedup/cleanup path shouldn't be
        // able to crash the caller. See this method's Javadoc on StorageService.
        storageService.updateDeliveryState("no-such-message", DeliveryState.READ);
    }

    @Test
    void markMessagesReadUpToOnlyTouchesTheGivenSendersMessagesAtOrBeforeTheWatermark() {
        PeerId alice = new PeerId("alice");
        PeerId bob = new PeerId("bob");
        storageService.saveConversation(new Conversation("c1", ConversationType.DIRECT, "Bob", 1000));

        // Deliberately using HlcTimestamp's real zero-padded string form (see that class's
        // Javadoc) rather than arbitrary strings, since markMessagesReadUpTo relies on plain
        // TEXT '<=' comparison agreeing with causal order.
        storageService.saveMessage(new Message("m1", "c1", alice, new DeviceId("0"),
                "0000000000000001000-0000000000-alice", "text/plain", "one".getBytes(), DeliveryState.SENT, 1));
        storageService.saveMessage(new Message("m2", "c1", alice, new DeviceId("0"),
                "0000000000000002000-0000000000-alice", "text/plain", "two".getBytes(), DeliveryState.SENT, 2));
        storageService.saveMessage(new Message("m3", "c1", alice, new DeviceId("0"),
                "0000000000000003000-0000000000-alice", "text/plain", "three-after-watermark".getBytes(), DeliveryState.SENT, 3));
        storageService.saveMessage(new Message("m-bob", "c1", bob, new DeviceId("0"),
                "0000000000000002500-0000000000-bob", "text/plain", "bobs-own".getBytes(), DeliveryState.DELIVERED, 4));

        storageService.markMessagesReadUpTo("c1", alice, "0000000000000002000-0000000000-alice");

        List<Message> messages = storageService.queryMessages("c1", new Pagination(null, 10));
        assertThat(stateOf(messages, "m1")).isEqualTo(DeliveryState.READ);
        assertThat(stateOf(messages, "m2")).isEqualTo(DeliveryState.READ);
        assertThat(stateOf(messages, "m3")).isEqualTo(DeliveryState.SENT); // after the watermark — untouched
        assertThat(stateOf(messages, "m-bob")).isEqualTo(DeliveryState.DELIVERED); // Bob's own — untouched
    }

    private static DeliveryState stateOf(List<Message> messages, String messageId) {
        return messages.stream()
                .filter(m -> m.messageId().equals(messageId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected message " + messageId + " to be present"))
                .state();
    }

    // ---- M6g-1: read queries ----

    @Test
    void listContactsReturnsAlphabeticalOrderAndHandlesEmptyTable() {
        assertThat(storageService.listContacts()).isEmpty();

        storageService.saveContact(new Contact(new PeerId("peer-3"), "Charlie", false, 300));
        storageService.saveContact(new Contact(new PeerId("peer-1"), "alice", true, 100));
        storageService.saveContact(new Contact(new PeerId("peer-4"), "bob", false, 250));
        storageService.saveContact(new Contact(new PeerId("peer-2"), "Bob", true, 200));

        List<Contact> contacts = storageService.listContacts();
        assertThat(contacts).hasSize(4);
        assertThat(contacts.get(0).displayName()).isEqualTo("alice");
        assertThat(contacts.get(0).peerId()).isEqualTo(new PeerId("peer-1"));
        // Case-insensitive sort, peer_id ASC tie-breaker: peer-2 before peer-4
        assertThat(contacts.get(1).peerId()).isEqualTo(new PeerId("peer-2"));
        assertThat(contacts.get(2).peerId()).isEqualTo(new PeerId("peer-4"));
        assertThat(contacts.get(3).displayName()).isEqualTo("Charlie");
    }

    @Test
    void getContactReturnsMatchingContactOrNull() {
        PeerId peerId = new PeerId("peer-alice");
        assertThat(storageService.getContact(peerId)).isNull();

        Contact contact = new Contact(peerId, "Alice", true, 12345L);
        storageService.saveContact(contact);

        Contact loaded = storageService.getContact(peerId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.peerId()).isEqualTo(peerId);
        assertThat(loaded.displayName()).isEqualTo("Alice");
        assertThat(loaded.verified()).isTrue();
        assertThat(loaded.addedAt()).isEqualTo(12345L);
    }

    @Test
    void getConversationReturnsMatchingConversationOrNull() {
        assertThat(storageService.getConversation("c-missing")).isNull();

        Conversation conv = new Conversation("c-test", ConversationType.DIRECT, "Direct Chat", 54321L);
        storageService.saveConversation(conv);

        Conversation loaded = storageService.getConversation("c-test");
        assertThat(loaded).isNotNull();
        assertThat(loaded.conversationId()).isEqualTo("c-test");
        assertThat(loaded.type()).isEqualTo(ConversationType.DIRECT);
        assertThat(loaded.name()).isEqualTo("Direct Chat");
        assertThat(loaded.createdAt()).isEqualTo(54321L);
    }

    @Test
    void listConversationsOrdersByMostRecentlyActiveMessageFirst() {
        assertThat(storageService.listConversations()).isEmpty();

        // 4 conversations: 2 with messages (different activity timestamps), 2 without (different created_at)
        storageService.saveConversation(new Conversation("c-inactive", ConversationType.DIRECT, "Inactive", 500));
        storageService.saveConversation(new Conversation("c-newer-inactive", ConversationType.DIRECT, "Newer Inactive", 800));
        storageService.saveConversation(new Conversation("c-old-activity", ConversationType.DIRECT, "Old Active", 100));
        storageService.saveConversation(new Conversation("c-recent-activity", ConversationType.DIRECT, "Recent Active", 200));

        PeerId sender = new PeerId("sender");
        // hlc timestamp: 19 digits physical time, '-' counter '-' node
        String hlc1000 = "0000000000000001000-0000000000-sender";
        String hlc2000 = "0000000000000002000-0000000000-sender";

        storageService.saveMessage(new Message("m1", "c-old-activity", sender, new DeviceId("0"),
                hlc1000, "text/plain", "hello".getBytes(), DeliveryState.SENT, 1000));
        storageService.saveMessage(new Message("m2", "c-recent-activity", sender, new DeviceId("0"),
                hlc2000, "text/plain", "world".getBytes(), DeliveryState.SENT, 2000));

        List<Conversation> ordered = storageService.listConversations();
        assertThat(ordered).extracting(Conversation::conversationId)
                .containsExactly("c-recent-activity", "c-old-activity", "c-newer-inactive", "c-inactive");
    }

    // ---- M6g-2: peer_routes ----

    @Test
    void getPeerRouteReturnsNullBeforeAnyObservation() {
        assertThat(storageService.getPeerRoute(new PeerId("peer-nobody"))).isNull();
    }

    @Test
    void upsertPeerRouteMergesRatherThanOverwritingUnobservedFields() {
        PeerId alice = new PeerId("peer-alice");

        PeerRoute afterFirst = storageService.upsertPeerRoute(new PeerRoute(
                alice, "/ip4/10.0.0.1/tcp/9000/p2p/peer-alice", null, null, null, 1000L));
        assertThat(afterFirst.directMultiaddr()).isEqualTo("/ip4/10.0.0.1/tcp/9000/p2p/peer-alice");
        assertThat(afterFirst.relayMultiaddr()).isNull();

        // Second observation only learns a relay address + display name -- must NOT erase the
        // direct multiaddr the first observation already established. This is the entire point
        // of the merge-upsert: real call sites (discovery, contacts.add, an inbound message's
        // senderAddress) each learn a different subset of a route at different times.
        PeerRoute afterSecond = storageService.upsertPeerRoute(new PeerRoute(
                alice, null, "/ip4/1.2.3.4/tcp/9100/p2p/relay", "Alice", null, 2000L));
        assertThat(afterSecond.directMultiaddr()).isEqualTo("/ip4/10.0.0.1/tcp/9000/p2p/peer-alice");
        assertThat(afterSecond.relayMultiaddr()).isEqualTo("/ip4/1.2.3.4/tcp/9100/p2p/relay");
        assertThat(afterSecond.displayName()).isEqualTo("Alice");
        assertThat(afterSecond.lastSeen()).isEqualTo(2000L); // always taken from the new observation

        // Third observation supplies a genuinely NEW direct multiaddr -- a non-null field always
        // wins over whatever was stored before, replacing the now-stale one.
        byte[] bundle = {1, 2, 3};
        PeerRoute afterThird = storageService.upsertPeerRoute(new PeerRoute(
                alice, "/ip4/10.0.0.2/tcp/9000/p2p/peer-alice", null, null, bundle, 3000L));
        assertThat(afterThird.directMultiaddr()).isEqualTo("/ip4/10.0.0.2/tcp/9000/p2p/peer-alice");
        assertThat(afterThird.relayMultiaddr()).isEqualTo("/ip4/1.2.3.4/tcp/9100/p2p/relay"); // still preserved
        assertThat(afterThird.displayName()).isEqualTo("Alice"); // still preserved
        assertThat(afterThird.preKeyBundle()).isEqualTo(bundle);

        PeerRoute fetched = storageService.getPeerRoute(alice);
        assertThat(fetched.directMultiaddr()).isEqualTo(afterThird.directMultiaddr());
        assertThat(fetched.lastSeen()).isEqualTo(3000L);
    }

    @Test
    void listPeerRoutesOrdersMostRecentlyObservedFirst() {
        storageService.upsertPeerRoute(new PeerRoute(new PeerId("peer-old"), "/ip4/10.0.0.1/tcp/9000", null, null, null, 500L));
        storageService.upsertPeerRoute(new PeerRoute(new PeerId("peer-new"), "/ip4/10.0.0.2/tcp/9000", null, null, null, 1500L));

        List<PeerRoute> routes = storageService.listPeerRoutes();
        assertThat(routes).extracting(PeerRoute::peerId)
                .containsExactly(new PeerId("peer-new"), new PeerId("peer-old"));
    }
}
