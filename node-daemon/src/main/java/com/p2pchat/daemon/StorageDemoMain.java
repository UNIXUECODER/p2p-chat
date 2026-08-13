package com.p2pchat.daemon;

import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.ConversationType;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.Pagination;
import com.p2pchat.storage.model.TransferState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * M3d: proves the SQLite schema (architecture-spec.md §9), the versioned migration runner, and
 * StorageService's five methods (§5) all work together — no networking, no crypto, purely the
 * persistence layer introduced in this milestone. Matches the pattern every prior milestone
 * demo used (e.g. CryptoDemoMain for M2a): prove the new piece in isolation before anything
 * else is built on top of it.
 *
 * Safe to re-run: each run generates fresh random IDs, so re-running just proves the migration
 * is idempotent (no error against an already-migrated database) rather than colliding with the
 * previous run's rows.
 */
public class StorageDemoMain {

    public static void main(String[] args) throws Exception {
        String dataDirName = System.getProperty("p2pchat.dataDir", ".p2p-chat-data");
        Path baseDir = Path.of(System.getProperty("user.dir"), dataDirName);

        System.out.println("Opening (and migrating, if needed) the SQLite database in " + baseDir + " ...");
        try (SqliteDatabase database = SqliteDatabase.openOrCreate(baseDir)) {
            StorageService storage = new SqliteStorageService(database);
            System.out.println("Migration applied. Database file: " + baseDir.resolve("p2p-chat.sqlite"));
            System.out.println();

            PeerId alice = new PeerId("demo-alice-" + UUID.randomUUID().toString().substring(0, 8));
            String conversationId = "c_" + UUID.randomUUID().toString().substring(0, 8);

            Contact contact = new Contact(alice, "Alice (demo)", false, System.currentTimeMillis());
            storage.saveContact(contact);
            System.out.println("Saved contact: " + contact.displayName() + " (" + contact.peerId() + ")");

            // M4e: use saveConversation instead of raw JDBC so this demo exercises the
            // same code path M5's real messaging will use — the whole point of M4e was
            // to ensure saveMessage works through StorageService, not around it.
            storage.saveConversation(new Conversation(conversationId, ConversationType.DIRECT, "Alice", System.currentTimeMillis()));

            Message message = new Message(
                    "m_" + UUID.randomUUID().toString().substring(0, 8),
                    conversationId,
                    alice,
                    DeviceId.DEFAULT,
                    "hlc-" + System.currentTimeMillis() + "-0",
                    "text/plain",
                    "Hello from the M3d storage demo.".getBytes(StandardCharsets.UTF_8),
                    DeliveryState.SENT,
                    System.currentTimeMillis()
            );
            storage.saveMessage(message);
            System.out.println("Saved message: \"" + new String(message.plaintext(), StandardCharsets.UTF_8) + "\"");

            List<Message> history = storage.queryMessages(conversationId, new Pagination(null, 50));
            String queriedText = history.isEmpty() ? null : new String(history.get(0).plaintext(), StandardCharsets.UTF_8);
            boolean roundTripOk = history.size() == 1
                    && history.get(0).messageId().equals(message.messageId())
                    && new String(message.plaintext(), StandardCharsets.UTF_8).equals(queriedText);
            System.out.println("Queried it back: " + history.size() + " message(s) in conversation " + conversationId
                    + " (round-trip correct: " + roundTripOk + ")");

            FileTransfer transfer = new FileTransfer(
                    "t_" + UUID.randomUUID().toString().substring(0, 8),
                    conversationId,
                    "demo-file.txt",
                    1024,
                    "deadbeef".repeat(8), // 64 hex chars — placeholder, not a real SHA-256
                    262144,
                    1,
                    TransferState.OFFERED,
                    null,
                    System.currentTimeMillis()
            );
            storage.saveFileMetadata(transfer);
            System.out.println("Saved file transfer metadata: " + transfer.fileName() + " (" + transfer.state() + ")");

            boolean transactionOk = storage.runInTransaction(() -> {
                storage.saveContact(new Contact(
                        new PeerId("demo-bob-" + UUID.randomUUID().toString().substring(0, 8)),
                        "Bob (demo)", false, System.currentTimeMillis()));
                return true;
            });
            System.out.println("Transactional write: " + transactionOk);

            System.out.println();
            boolean allOk = roundTripOk && transactionOk;
            System.out.println(allOk
                    ? "M3d CONFIRMED: schema + migrations + StorageService all work correctly."
                    : "M3d FAILED: something did not match \u2014 check the output above.");
        }
    }
}
