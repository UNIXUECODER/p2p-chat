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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** SQLite-backed {@link StorageService}. See that interface's Javadoc for what is and isn't in scope for M3d. */
public final class SqliteStorageService implements StorageService {

    private final SqliteDatabase database;

    public SqliteStorageService(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public void saveMessage(Message message) {
        String sql = "INSERT INTO messages " +
                "(message_id, conversation_id, sender_peer_id, sender_device_id, hlc_timestamp, " +
                " content_type, plaintext_cache, delivery_state, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        runUpdate(sql, statement -> {
            statement.setString(1, message.messageId());
            statement.setString(2, message.conversationId());
            statement.setString(3, message.senderPeerId().value());
            statement.setString(4, message.senderDeviceId().value());
            statement.setString(5, message.hlcTimestamp());
            statement.setString(6, message.contentType());
            statement.setString(7, textOrNull(message.plaintext()));
            statement.setString(8, message.state().name());
            statement.setLong(9, message.createdAt());
        });
    }

    @Override
    public List<Message> queryMessages(String conversationId, Pagination page) {
        String cursor = (page != null) ? page.cursor() : null;
        boolean hasCursor = cursor != null && !cursor.isBlank();
        int limit = (page != null && page.limit() > 0) ? page.limit() : 50;

        String sql = hasCursor
                ? "SELECT message_id, conversation_id, sender_peer_id, sender_device_id, hlc_timestamp, " +
                  "       content_type, plaintext_cache, delivery_state, created_at " +
                  "FROM messages WHERE conversation_id = ? AND hlc_timestamp > ? " +
                  "ORDER BY hlc_timestamp ASC LIMIT ?"
                : "SELECT message_id, conversation_id, sender_peer_id, sender_device_id, hlc_timestamp, " +
                  "       content_type, plaintext_cache, delivery_state, created_at " +
                  "FROM messages WHERE conversation_id = ? " +
                  "ORDER BY hlc_timestamp ASC LIMIT ?";

        List<Message> results = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, conversationId);
            if (hasCursor) {
                statement.setString(2, cursor);
                statement.setInt(3, limit);
            } else {
                statement.setInt(2, limit);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapMessage(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query messages for conversation " + conversationId, e);
        }
        return results;
    }

    @Override
    public void saveContact(Contact contact) {
        String sql = "INSERT INTO contacts (peer_id, display_name, verified, added_at) VALUES (?, ?, ?, ?)";
        runUpdate(sql, statement -> {
            statement.setString(1, contact.peerId().value());
            statement.setString(2, contact.displayName());
            statement.setInt(3, contact.verified() ? 1 : 0);
            statement.setLong(4, contact.addedAt());
        });
    }

    @Override
    public void saveFileMetadata(FileTransfer transfer) {
        // INSERT OR IGNORE, not a plain insert — unlike saveMessage/saveContact. Reason:
        // file_chunk_state has a foreign key on file_transfers(transfer_id) (see V001__init.sql),
        // so markChunkReceived requires this row to already exist. A resumed transfer legitimately
        // calls this again for a transferId that's already stored (see FileReceiverMain), and that
        // must not throw.
        String sql = "INSERT OR IGNORE INTO file_transfers " +
                "(transfer_id, conversation_id, file_name, file_size, file_hash, chunk_size, total_chunks, state, local_path, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        runUpdate(sql, statement -> {
            statement.setString(1, transfer.transferId());
            statement.setString(2, transfer.conversationId());
            statement.setString(3, transfer.fileName());
            statement.setLong(4, transfer.fileSize());
            statement.setString(5, transfer.fileHash());
            statement.setInt(6, transfer.chunkSize());
            statement.setInt(7, transfer.totalChunks());
            statement.setString(8, transfer.state().name());
            statement.setString(9, transfer.localPath());
            statement.setLong(10, transfer.createdAt());
        });
    }

    @Override
    public void updateTransferState(String transferId, TransferState newState) {
        String sql = "UPDATE file_transfers SET state = ? WHERE transfer_id = ?";
        runUpdate(sql, statement -> {
            statement.setString(1, newState.name());
            statement.setString(2, transferId);
        });
    }

    @Override
    public TransferState getTransferState(String transferId) {
        String sql = "SELECT state FROM file_transfers WHERE transfer_id = ?";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, transferId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? TransferState.valueOf(resultSet.getString("state")) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get transfer state for " + transferId, e);
        }
    }

    @Override
    public void resetChunkState(String transferId) {
        runInTransaction(() -> {
            String sqlChunks = "DELETE FROM file_chunk_state WHERE transfer_id = ?";
            runUpdate(sqlChunks, statement -> statement.setString(1, transferId));
            String sqlTransfer = "UPDATE file_transfers SET state = ? WHERE transfer_id = ?";
            runUpdate(sqlTransfer, statement -> {
                statement.setString(1, TransferState.OFFERED.name());
                statement.setString(2, transferId);
            });
            return null;
        });
    }

    @Override
    public void saveConversation(Conversation conversation) {
        // INSERT OR IGNORE, same reasoning as saveFileMetadata: messages has a foreign key on
        // conversations(conversation_id) (see V001__init.sql), so saveMessage requires this row
        // to exist first. Callers (e.g. a 1:1 send path) must be free to call this before every
        // saveMessage without checking existence themselves — a plain insert would throw on the
        // second message to the same conversation.
        String sql = "INSERT OR IGNORE INTO conversations (conversation_id, type, name, created_at) VALUES (?, ?, ?, ?)";
        runUpdate(sql, statement -> {
            statement.setString(1, conversation.conversationId());
            statement.setString(2, conversation.type().name());
            statement.setString(3, conversation.name());
            statement.setLong(4, conversation.createdAt());
        });
    }

    @Override
    public void markChunkReceived(String transferId, int chunkIndex) {
        String sql = "INSERT OR IGNORE INTO file_chunk_state (transfer_id, chunk_index, received) VALUES (?, ?, 1)";
        runUpdate(sql, statement -> {
            statement.setString(1, transferId);
            statement.setInt(2, chunkIndex);
        });
    }

    @Override
    public List<Integer> missingChunks(String transferId, int totalChunks) {
        Set<Integer> received = new HashSet<>();
        String sql = "SELECT chunk_index FROM file_chunk_state WHERE transfer_id = ? AND received = 1";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, transferId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    received.add(resultSet.getInt("chunk_index"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query received chunks for transfer " + transferId, e);
        }

        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!received.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    @Override
    public boolean hasMessage(String messageId) {
        String sql = "SELECT 1 FROM messages WHERE message_id = ? LIMIT 1";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check for existing message " + messageId, e);
        }
    }

    @Override
    public void updateDeliveryState(String messageId, DeliveryState newState) {
        String sql = "UPDATE messages SET delivery_state = ? WHERE message_id = ?";
        runUpdate(sql, statement -> {
            statement.setString(1, newState.name());
            statement.setString(2, messageId);
        });
    }

    @Override
    public void markMessagesReadUpTo(String conversationId, PeerId senderPeerId, String readUpToHlcTimestamp) {
        // sender_peer_id = ? restricts this to THIS node's own outgoing messages in the
        // conversation — see this method's own Javadoc on StorageService for why that's not
        // optional. delivery_state != 'READ' isn't required for correctness (re-marking READ as
        // READ is harmless) but avoids rewriting rows that don't need it.
        String sql = "UPDATE messages SET delivery_state = ? " +
                "WHERE conversation_id = ? AND sender_peer_id = ? AND hlc_timestamp <= ? AND delivery_state != ?";
        runUpdate(sql, statement -> {
            statement.setString(1, DeliveryState.READ.name());
            statement.setString(2, conversationId);
            statement.setString(3, senderPeerId.value());
            statement.setString(4, readUpToHlcTimestamp);
            statement.setString(5, DeliveryState.READ.name());
        });
    }

    @Override
    public List<Conversation> listConversations() {
        List<Conversation> conversations = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT conversation_id, type, name, created_at FROM conversations")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    conversations.add(mapConversation(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list conversations", e);
        }

        // Most recent hlc_timestamp per conversation, in one query rather than one per
        // conversation — same "don't do N+1 queries when a single aggregate covers it" instinct
        // as missingChunks' own single received-set query below. A conversation absent from this
        // map has no messages yet.
        Map<String, String> latestHlcByConversation = new HashMap<>();
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT conversation_id, MAX(hlc_timestamp) AS latest FROM messages GROUP BY conversation_id")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    latestHlcByConversation.put(resultSet.getString("conversation_id"), resultSet.getString("latest"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to compute latest activity per conversation", e);
        }

        conversations.sort((a, b) -> {
            long keyA = activityKeyMillis(a, latestHlcByConversation);
            long keyB = activityKeyMillis(b, latestHlcByConversation);
            int byActivity = Long.compare(keyB, keyA); // most-recent first
            return byActivity != 0 ? byActivity : a.conversationId().compareTo(b.conversationId());
        });
        return conversations;
    }

    /**
     * The sort key {@link #listConversations} orders by: the physical-time component of the
     * conversation's most recent message, or {@code createdAt} if it has none yet. Safe to read
     * just the leading 19 characters of {@code hlc_timestamp} as epoch millis specifically
     * because {@code core-messaging.HlcTimestamp#toString()}'s fixed-width zero-padded encoding
     * puts the physical component there unconditionally (19 digits, then a {@code '-'}) — the
     * same encoding fact {@link #markMessagesReadUpTo} already relies on for its own TEXT
     * comparison, not a new assumption introduced here. Not parsed via {@code HlcTimestamp}
     * itself: core-storage has no dependency on core-messaging, and a 19-character substring
     * parse doesn't need one.
     */
    private static long activityKeyMillis(Conversation conversation, Map<String, String> latestHlcByConversation) {
        String latest = latestHlcByConversation.get(conversation.conversationId());
        if (latest == null) {
            return conversation.createdAt();
        }
        return Long.parseLong(latest.substring(0, 19));
    }

    @Override
    public List<Contact> listContacts() {
        List<Contact> results = new ArrayList<>();
        String sql = "SELECT peer_id, display_name, verified, added_at FROM contacts " +
                "ORDER BY display_name COLLATE NOCASE ASC, peer_id ASC";
        try (PreparedStatement statement = database.connection().prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                results.add(mapContact(resultSet));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list contacts", e);
        }
        return results;
    }

    @Override
    public Conversation getConversation(String conversationId) {
        String sql = "SELECT conversation_id, type, name, created_at FROM conversations WHERE conversation_id = ?";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapConversation(resultSet) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get conversation " + conversationId, e);
        }
    }

    @Override
    public Contact getContact(PeerId peerId) {
        String sql = "SELECT peer_id, display_name, verified, added_at FROM contacts WHERE peer_id = ?";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, peerId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapContact(resultSet) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get contact " + peerId, e);
        }
    }

    private static Conversation mapConversation(ResultSet resultSet) throws SQLException {
        return new Conversation(
                resultSet.getString("conversation_id"),
                ConversationType.valueOf(resultSet.getString("type")),
                resultSet.getString("name"),
                resultSet.getLong("created_at")
        );
    }

    private static Contact mapContact(ResultSet resultSet) throws SQLException {
        return new Contact(
                new PeerId(resultSet.getString("peer_id")),
                resultSet.getString("display_name"),
                resultSet.getInt("verified") != 0,
                resultSet.getLong("added_at")
        );
    }

    @Override
    public PeerRoute upsertPeerRoute(PeerRoute observed) {
        // COALESCE-on-null merge, not a blind overwrite -- see this method's own interface
        // Javadoc for why: the real call sites (discovery lookup, an inbound message's
        // senderAddress, contacts.add, a relay registration) each learn a different subset of a
        // route at different times, so a null field on `observed` means "this call didn't learn
        // anything new here," not "clear it." last_seen is the one field taken from `excluded`
        // unconditionally -- every call is a real, fresh observation, so it should always win.
        String sql = "INSERT INTO peer_routes (peer_id, direct_multiaddr, relay_multiaddr, display_name, pre_key_bundle, last_seen) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(peer_id) DO UPDATE SET " +
                "direct_multiaddr = COALESCE(excluded.direct_multiaddr, peer_routes.direct_multiaddr), " +
                "relay_multiaddr = COALESCE(excluded.relay_multiaddr, peer_routes.relay_multiaddr), " +
                "display_name = COALESCE(excluded.display_name, peer_routes.display_name), " +
                "pre_key_bundle = COALESCE(excluded.pre_key_bundle, peer_routes.pre_key_bundle), " +
                "last_seen = excluded.last_seen";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, observed.peerId().value());
            statement.setString(2, observed.directMultiaddr());
            statement.setString(3, observed.relayMultiaddr());
            statement.setString(4, observed.displayName());
            statement.setBytes(5, observed.preKeyBundle());
            statement.setLong(6, observed.lastSeen());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert peer route for " + observed.peerId(), e);
        }
        // A caller passing a freshly-observed route almost always wants to know the merged
        // result, not just "it worked" -- one query here saves every caller a follow-up
        // getPeerRoute just to see what actually landed after the merge (see interface Javadoc).
        PeerRoute merged = getPeerRoute(observed.peerId());
        if (merged == null) {
            // Would mean the INSERT above silently didn't happen -- genuinely unexpected, not a
            // normal outcome any caller should have to handle, so this fails loudly rather than
            // returning null and letting a NullPointerException surface somewhere unrelated.
            throw new IllegalStateException("Upserted peer route for " + observed.peerId() + " but it cannot be read back");
        }
        return merged;
    }

    @Override
    public PeerRoute getPeerRoute(PeerId peerId) {
        String sql = "SELECT peer_id, direct_multiaddr, relay_multiaddr, display_name, pre_key_bundle, last_seen " +
                "FROM peer_routes WHERE peer_id = ?";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, peerId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapPeerRoute(resultSet) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get peer route for " + peerId, e);
        }
    }

    @Override
    public List<PeerRoute> listPeerRoutes() {
        List<PeerRoute> results = new ArrayList<>();
        String sql = "SELECT peer_id, direct_multiaddr, relay_multiaddr, display_name, pre_key_bundle, last_seen " +
                "FROM peer_routes ORDER BY last_seen DESC";
        try (PreparedStatement statement = database.connection().prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                results.add(mapPeerRoute(resultSet));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list peer routes", e);
        }
        return results;
    }

    private static PeerRoute mapPeerRoute(ResultSet resultSet) throws SQLException {
        return new PeerRoute(
                new PeerId(resultSet.getString("peer_id")),
                resultSet.getString("direct_multiaddr"),
                resultSet.getString("relay_multiaddr"),
                resultSet.getString("display_name"),
                resultSet.getBytes("pre_key_bundle"),
                resultSet.getLong("last_seen")
        );
    }

    @Override
    public <T> T runInTransaction(Supplier<T> work) {
        Connection connection = database.connection();
        try {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.get();
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Transaction failed", e);
        }
    }

    private Message mapMessage(ResultSet resultSet) throws SQLException {
        String plaintextText = resultSet.getString("plaintext_cache");
        byte[] plaintext = plaintextText != null ? plaintextText.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return new Message(
                resultSet.getString("message_id"),
                resultSet.getString("conversation_id"),
                new PeerId(resultSet.getString("sender_peer_id")),
                new DeviceId(resultSet.getString("sender_device_id")),
                resultSet.getString("hlc_timestamp"),
                resultSet.getString("content_type"),
                plaintext,
                DeliveryState.valueOf(resultSet.getString("delivery_state")),
                resultSet.getLong("created_at")
        );
    }

    private static String textOrNull(byte[] plaintext) {
        return (plaintext != null && plaintext.length > 0) ? new String(plaintext, StandardCharsets.UTF_8) : null;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private void runUpdate(String sql, StatementBinder binder) {
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Storage write failed: " + e.getMessage(), e);
        }
    }
}
