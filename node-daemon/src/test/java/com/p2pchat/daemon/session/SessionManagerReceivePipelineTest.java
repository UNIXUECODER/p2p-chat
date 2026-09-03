package com.p2pchat.daemon.session;

import com.p2pchat.daemon.dispatch.ApplicationMessageRouter;
import com.p2pchat.daemon.dispatch.DispatchedMessage;
import com.p2pchat.filetransfer.FileChunker;
import com.p2pchat.filetransfer.FileKey;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.HybridLogicalClock;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.messaging.wire.DeliveryReceiptPayload;
import com.p2pchat.messaging.wire.ReadReceiptPayload;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.ConnectivityStatus;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        // The auto-delivery-receipt is dispatched asynchronously via OutboundMessageService; await receipt emission
        awaitSentCount(1);
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
        awaitSentCount(2);
        assertThat(fakeNetwork.sentTo()).hasSize(2);
    }

    @Test
    void aMessageWithImplausibleFutureDriftIsRejectedNotPersistedNorAcknowledged() throws Exception {
        // Regression test for the bug fixed alongside M6g-1: SessionManager previously never
        // called HybridLogicalClock.checkDrift at all (see that method's own call site in
        // handleChatMessagePayload for the full history), so a message claiming a timestamp far
        // in the future was accepted unconditionally. ChatListenerMain's own equivalent gate
        // fully rejects such a message -- not persisted, not acknowledged, clock not advanced --
        // and this pins SessionManager to that same behavior.
        long tooFarFuture = System.currentTimeMillis() + HybridLogicalClock.DEFAULT_MAX_FUTURE_DRIFT.toMillis() + 60_000;
        ChatMessagePayload chat = new ChatMessagePayload(
                "a1a1a1a1-0000-4000-8000-000000000005", senderAddress,
                new HlcTimestamp(tooFarFuture, 0, senderPeerId.value()), "direct-a-b", "text/plain",
                "from the future".getBytes(StandardCharsets.UTF_8), null);

        sessionManager.handleDecryptedPlaintext(senderPeerId, ChatMessageCodec.encode(chat));

        assertThat(storage.hasMessage(chat.messageId())).isFalse();
        // Unlike the other tests, nothing is ever queued to send for a rejected message -- no
        // async completion to await, but a short grace period guards against a delayed false pass.
        Thread.sleep(200);
        assertThat(fakeNetwork.sentTo()).isEmpty();
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

    // ---- M6g-3: DaemonEventListener wiring. A separate, locally-constructed SessionManager per
    // test below (not the shared `sessionManager` field, which uses the 4-arg constructor and
    // DaemonEventListener.NONE) -- keeps the existing five tests above completely undisturbed
    // while proving the new listener wiring against its own isolated instance.
    //
    // DaemonEventListener calls run on their own eventExecutor (see SessionManager's own class
    // Javadoc for why), so assertions below poll briefly rather than assuming synchronous
    // delivery -- same reasoning as awaitSentCount already established for the auto-delivery-
    // receipt path.
    //
    // Not covered here: onNetworkStatusChanged. Its two real trigger points (handleInboundEnvelope's
    // pre/post-decrypt session check, and sendChatMessage's establishSession call) both require
    // capabilities FakeSecureSessionServiceForTest deliberately doesn't provide -- decrypt() and
    // establishSession() both throw UnsupportedOperationException by design (see that class's own
    // Javadoc: "Not testing encryption correctness here at all"), and handleDecryptedPlaintext
    // (this test class's one seam) is called after decrypt would already have happened, bypassing
    // the exact check that fires this event. Extending the fake to support it would risk the
    // "only encrypt needs to actually work" contract every other test in this file already
    // depends on -- a real, honestly-named gap rather than a forced, low-value test.

    @Test
    void aNewChatMessageFiresOnMessageReceivedWithTheExactPersistedMessage() throws Exception {
        List<Message> received = new CopyOnWriteArrayList<>();
        DaemonEventListener capturingListener = new DaemonEventListener() {
            @Override
            public void onMessageReceived(Message message) {
                received.add(message);
            }
        };
        SessionManager sm = new SessionManager(fakeNetwork, storage, null, new FileTransferHandler() {
        }, capturingListener);
        sm.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), fakeSessions);
        try {
            ChatMessagePayload chat = new ChatMessagePayload(
                    "a1a1a1a1-0000-4000-8000-000000000006", senderAddress,
                    new HlcTimestamp(1000L, 0, senderPeerId.value()), "direct-a-b", "text/plain",
                    "event test".getBytes(StandardCharsets.UTF_8), null);

            sm.handleDecryptedPlaintext(senderPeerId, ChatMessageCodec.encode(chat));

            awaitSize(received, 1);
            assertThat(received).hasSize(1);
            assertThat(received.get(0).messageId()).isEqualTo(chat.messageId());
            assertThat(received.get(0).plaintext()).isEqualTo("event test".getBytes(StandardCharsets.UTF_8));
        } finally {
            sm.close();
        }
    }

    @Test
    void aDuplicateMessageDoesNotFireOnMessageReceivedASecondTime() throws Exception {
        List<Message> received = new CopyOnWriteArrayList<>();
        DaemonEventListener capturingListener = new DaemonEventListener() {
            @Override
            public void onMessageReceived(Message message) {
                received.add(message);
            }
        };
        SessionManager sm = new SessionManager(fakeNetwork, storage, null, new FileTransferHandler() {
        }, capturingListener);
        sm.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), fakeSessions);
        try {
            ChatMessagePayload chat = new ChatMessagePayload(
                    "a1a1a1a1-0000-4000-8000-000000000007", senderAddress,
                    new HlcTimestamp(1000L, 0, senderPeerId.value()), "direct-a-b", "text/plain",
                    "sent twice".getBytes(StandardCharsets.UTF_8), null);
            byte[] wire = ChatMessageCodec.encode(chat);

            sm.handleDecryptedPlaintext(senderPeerId, wire);
            sm.handleDecryptedPlaintext(senderPeerId, wire); // the sender's earlier ack was "lost"

            awaitSize(received, 1);
            Thread.sleep(200); // grace period -- proving a second event does NOT eventually arrive, not just that it hasn't yet
            assertThat(received).hasSize(1);
        } finally {
            sm.close();
        }
    }

    @Test
    void aDeliveryReceiptFiresOnDeliveryStateChanged() throws Exception {
        List<DeliveryState> received = new CopyOnWriteArrayList<>();
        DaemonEventListener capturingListener = new DaemonEventListener() {
            @Override
            public void onDeliveryStateChanged(String messageId, DeliveryState newState) {
                received.add(newState);
            }
        };
        SessionManager sm = new SessionManager(fakeNetwork, storage, null, new FileTransferHandler() {
        }, capturingListener);
        sm.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), fakeSessions);
        try {
            insertRawSentMessage("a1a1a1a1-0000-4000-8000-000000000008", "SENDING");
            DeliveryReceiptPayload receipt = new DeliveryReceiptPayload("direct-a-b",
                    "a1a1a1a1-0000-4000-8000-000000000008");

            sm.handleDecryptedPlaintext(senderPeerId, ChatMessageCodec.encode(receipt));

            awaitSize(received, 1);
            assertThat(received).containsExactly(DeliveryState.DELIVERED);
        } finally {
            sm.close();
        }
    }

    // ---- M6g-3: Direct unit tests for SessionManager.sendFile and SessionManager.acceptFileTransfer

    @Test
    void sendFileRejectsNonExistentFileWithUnreachableStatus(@TempDir Path tempDir) throws Exception {
        Path missingFile = tempDir.resolve("missing.bin");
        CompletableFuture<FileSendResult> future = sessionManager.sendFile(
                senderPeerId, senderAddress, null, missingFile);

        FileSendResult result = future.get();
        assertThat(result.status()).isEqualTo(ConnectivityStatus.UNREACHABLE);
        // M6g-4: no transfer was ever registered for a file that doesn't exist -- see
        // FileSendResult's own Javadoc for why null here is deliberate, not an oversight.
        assertThat(result.transferId()).isNull();
        assertThat(fakeNetwork.sentTo()).isEmpty();
    }

    @Test
    void sendFileRegistersOutgoingTransferEncryptsOfferAndSendsOutbound(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("send-test.bin");
        byte[] fileBytes = new byte[750];
        new java.util.Random(123).nextBytes(fileBytes);
        Files.write(testFile, fileBytes);

        class CapturingFileTransferHandler implements FileTransferHandler {
            String registeredTransferId;
            Path registeredPath;
            FileKey registeredKey;
            int registeredChunkSize;
            PeerId registeredTarget;

            @Override
            public void registerOutgoingTransfer(String transferId, Path sourceFile, FileKey fileKey, int chunkSize,
                                                 PeerId targetPeerId, String targetDirectMultiaddr, String targetRelayMultiaddr) {
                this.registeredTransferId = transferId;
                this.registeredPath = sourceFile;
                this.registeredKey = fileKey;
                this.registeredChunkSize = chunkSize;
                this.registeredTarget = targetPeerId;
            }
        }

        CapturingFileTransferHandler capturingHandler = new CapturingFileTransferHandler();
        SessionManager sm = new SessionManager(fakeNetwork, storage, null, capturingHandler);
        sm.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), fakeSessions);

        try {
            CompletableFuture<FileSendResult> future = sm.sendFile(
                    senderPeerId, senderAddress, null, testFile);

            FileSendResult result = future.get();
            assertThat(result.status()).isEqualTo(ConnectivityStatus.DIRECT);
            // M6g-4: the transferId sendFile hands back must be the SAME one it registered with
            // FileTransferHandler -- not merely non-null. This is the exact bug FileSendResult's
            // own Javadoc names as the risk a caller-generated id would have created.
            assertThat(result.transferId()).isEqualTo(capturingHandler.registeredTransferId);
            assertThat(capturingHandler.registeredTransferId).isNotNull();
            assertThat(capturingHandler.registeredPath).isEqualTo(testFile);
            assertThat(capturingHandler.registeredKey).isNotNull();
            assertThat(capturingHandler.registeredKey.bytes()).hasSize(32);
            assertThat(capturingHandler.registeredChunkSize).isEqualTo(FileChunker.DEFAULT_CHUNK_SIZE_BYTES);
            assertThat(capturingHandler.registeredTarget).isEqualTo(senderPeerId);

            // Outbound send occurred
            awaitSentCount(1);
            assertThat(fakeNetwork.sentTo()).contains(senderAddress);

            // Decode the encrypted plaintext passed to fakeSessions.encrypt
            byte[] encryptedOfferPlaintext = fakeSessions.encryptedPlaintexts().get(fakeSessions.encryptedPlaintexts().size() - 1);
            DispatchedMessage dispatched = ApplicationMessageRouter.dispatch(encryptedOfferPlaintext);
            assertThat(dispatched).isInstanceOf(DispatchedMessage.FileTransfer.class);
            com.p2pchat.filetransfer.wire.FileTransferMessage ftMessage = ((DispatchedMessage.FileTransfer) dispatched).message();
            assertThat(ftMessage).isInstanceOf(FileOfferPayload.class);
            FileOfferPayload offer = (FileOfferPayload) ftMessage;

            assertThat(offer.transferId()).isEqualTo(capturingHandler.registeredTransferId);
            assertThat(offer.fileName()).isEqualTo("send-test.bin");
            assertThat(offer.fileSize()).isEqualTo(750L);
            assertThat(offer.fileHash()).isEqualTo(FileChunker.sha256HexOfFile(testFile));
            assertThat(offer.totalChunks()).isEqualTo(1);
            assertThat(offer.fileKey()).isEqualTo(capturingHandler.registeredKey.bytes());
        } finally {
            sm.close();
        }
    }

    @Test
    void acceptFileTransferDelegatesDirectlyToFileTransferHandler(@TempDir Path tempDir) {
        java.util.concurrent.atomic.AtomicReference<String> acceptedTransferId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Path> acceptedPath = new java.util.concurrent.atomic.AtomicReference<>();

        FileTransferHandler handler = new FileTransferHandler() {
            @Override
            public void acceptFileTransfer(String transferId, Path savePath) {
                acceptedTransferId.set(transferId);
                acceptedPath.set(savePath);
            }
        };

        SessionManager sm = new SessionManager(fakeNetwork, storage, null, handler);
        sm.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), fakeSessions);

        try {
            Path dest = tempDir.resolve("saved.bin");
            sm.acceptFileTransfer("transfer-999", dest);

            assertThat(acceptedTransferId.get()).isEqualTo("transfer-999");
            assertThat(acceptedPath.get()).isEqualTo(dest);
        } finally {
            sm.close();
        }
    }

    @Test
    void sendFileAndAcceptFileTransferThrowIllegalStateExceptionBeforeStart(@TempDir Path tempDir) {
        SessionManager unstarted = new SessionManager(fakeNetwork, storage, null, new FileTransferHandler() {
        });
        Path dummy = tempDir.resolve("dummy.bin");

        assertThatThrownBy(() -> unstarted.sendFile(senderPeerId, senderAddress, null, dummy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be called before use");

        assertThatThrownBy(() -> unstarted.acceptFileTransfer("t1", dummy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be called before use");
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

    private void awaitSentCount(int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (fakeNetwork.sentTo().size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private void awaitSize(List<?> list, int expectedSize) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (list.size() < expectedSize && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
