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
}
