package com.p2pchat.daemon.session;

import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.HybridLogicalClock;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.messaging.wire.DeliveryReceiptPayload;
import com.p2pchat.messaging.wire.ReadReceiptPayload;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6e-2. {@code SessionManager.handleDecryptedPlaintext} is the seam this project's testable-
 * pieces discipline was built around: dispatch, dedup, and persistence logic that needs neither
 * jvm-libp2p nor libsignal-client to verify, exercised here against a real SQLite database —
 * same rigor as M6b (fake network) and M6e-1 (real SQLite, fake crypto), applied to the piece of
 * M6e-2 that's actually possible to run for real in a sandbox that can't reach either dependency.
 *
 * <p>No {@code getMessage}/{@code getDeliveryState} accessor exists on {@code StorageService}
 * yet (confirmed by reading its current interface before writing this, not assumed) — delivery-
 * state assertions below query the underlying SQLite table directly via JDBC, the same honest
 * "verify via raw SQL where the public interface doesn't expose a reader yet" approach M6e-1's
 * own debugging already established.
 */
class SessionManagerReceivePipelineTest {

    private SqliteDatabase database;
    private SqliteStorageService storage;
    private SessionManager sessionManager;
    private FakeSecureSessionServiceForTest fakeSessions;
    private FakeNetworkForSessionTest fakeNetwork;

    private final PeerId ownPeerId = PeerId.of("12D3KooWOwnNode");
    private final PeerId senderPeerId = PeerId.of("12D3KooWSender");
    private final String senderAddress = "/ip4/127.0.0.1/tcp/9100/p2p/12D3KooWSender";

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
        fakeNetwork = new FakeNetworkForSessionTest();
        fakeSessions = new FakeSecureSessionServiceForTest();

        // signalStore is null deliberately -- handleDecryptedPlaintext's call path never
        // touches it (only sendChatMessage/hasSession do, neither exercised by this test class).
        sessionManager = new SessionManager(fakeNetwork, storage, null, new FileTransferHandler() {
        });
        sessionManager.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), fakeSessions);
    }

    @AfterEach
    void tearDown() throws Exception {
        sessionManager.close();
        database.close();
    }

    @Test
    void aNewChatMessageIsPersistedAndAcknowledged() throws Exception {
        ChatMessagePayload chat = new ChatMessagePayload(
                "a1a1a1a1-0000-4000-8000-000000000001", senderAddress,
                new HlcTimestamp(1000L, 0, senderPeerId.value()), "direct-a-b", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8), null);

        sessionManager.handleDecryptedPlaintext(senderPeerId, ChatMessageCodec.encode(chat));

        assertThat(storage.hasMessage(chat.messageId())).isTrue();
        assertThat(plaintextCacheOf(chat.messageId())).isEqualTo("hello");
        // The auto-delivery-receipt: sent to chat.senderAddress(), not looked up any other way.
        assertThat(fakeNetwork.sentTo()).containsExactly(senderAddress);
        assertThat(fakeSessions.encryptedPlaintexts()).hasSize(1);
    }

    @Test
    void aDuplicateMessageIsNotPersistedTwiceButIsStillAcknowledged() throws Exception {
        ChatMessagePayload chat = new ChatMessagePayload(
                "a1a1a1a1-0000-4000-8000-000000000002", senderAddress,
                new HlcTimestamp(1000L, 0, senderPeerId.value()), "direct-a-b", "text/plain",
                "hello again".getBytes(StandardCharsets.UTF_8), null);
        byte[] wire = ChatMessageCodec.encode(chat);

        sessionManager.handleDecryptedPlaintext(senderPeerId, wire);
        sessionManager.handleDecryptedPlaintext(senderPeerId, wire); // the sender's earlier ack was "lost"

        assertThat(countMessageRows(chat.messageId())).isEqualTo(1); // not inserted twice
        // Acknowledged both times -- the sender has no way to know their first ack was lost.
        assertThat(fakeNetwork.sentTo()).hasSize(2);
    }

    @Test
    void aDeliveryReceiptUpdatesTheOriginalMessagesDeliveryState() throws Exception {
        // A message THIS node sent earlier, now being acknowledged by its recipient.
        insertRawSentMessage("a1a1a1a1-0000-4000-8000-000000000003", "SENDING");
        DeliveryReceiptPayload receipt = new DeliveryReceiptPayload("direct-a-b",
                "a1a1a1a1-0000-4000-8000-000000000003");

        sessionManager.handleDecryptedPlaintext(senderPeerId, ChatMessageCodec.encode(receipt));

        assertThat(deliveryStateOf("a1a1a1a1-0000-4000-8000-000000000003")).isEqualTo("DELIVERED");
    }

    @Test
    void aReadReceiptMarksThisNodesOwnMessagesReadUpToTheGivenTimestamp() throws Exception {
        insertRawSentMessageWithTimestamp("a1a1a1a1-0000-4000-8000-000000000004",
                new HlcTimestamp(500L, 0, ownPeerId.value()).toString());
        ReadReceiptPayload readReceipt = new ReadReceiptPayload("direct-a-b",
                new HlcTimestamp(1000L, 0, senderPeerId.value()));

        sessionManager.handleDecryptedPlaintext(senderPeerId, ChatMessageCodec.encode(readReceipt));

        assertThat(deliveryStateOf("a1a1a1a1-0000-4000-8000-000000000004")).isEqualTo("READ");
    }

    // ---------------------------------------------------------------- raw SQL verification helpers

    private String plaintextCacheOf(String messageId) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT plaintext_cache FROM messages WHERE message_id = '" + messageId + "'")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private String deliveryStateOf(String messageId) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT delivery_state FROM messages WHERE message_id = '" + messageId + "'")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int countMessageRows(String messageId) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM messages WHERE message_id = '" + messageId + "'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void insertRawSentMessage(String messageId, String deliveryState) throws Exception {
        insertRawSentMessageWithTimestamp(messageId, new HlcTimestamp(1L, 0, ownPeerId.value()).toString());
    }

    private void insertRawSentMessageWithTimestamp(String messageId, String hlcTimestamp) throws Exception {
        try (Statement statement = database.connection().createStatement()) {
            statement.execute("INSERT INTO conversations (conversation_id, type, created_at) " +
                    "VALUES ('direct-a-b', 'DIRECT', 0)");
            statement.execute("INSERT INTO messages (message_id, conversation_id, sender_peer_id, " +
                    "hlc_timestamp, content_type, delivery_state, created_at) VALUES ('" + messageId +
                    "', 'direct-a-b', '" + ownPeerId.value() + "', '" + hlcTimestamp + "', 'text/plain', 'SENDING', 0)");
        }
    }
}
