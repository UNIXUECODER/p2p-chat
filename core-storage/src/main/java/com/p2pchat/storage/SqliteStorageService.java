package com.p2pchat.storage;

import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.Pagination;
import com.p2pchat.storage.model.TransferState;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
