package com.p2pchat.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link RelaySpool} backed by a single SQLite connection, confined entirely to one dedicated
 * background thread.
 *
 * <p><b>Why a dedicated single-thread executor, specifically:</b> {@link RelayRegistry}'s own
 * callbacks run directly on Netty's I/O worker threads, one per connection, so this relay
 * genuinely has multiple threads calling into whatever backs the spool at once — confirmed by
 * reading {@code RelayRegistry}'s own {@code registered} field directly (a {@code
 * ConcurrentHashMap}, because it already has to cope with exactly this). SQLite JDBC connections
 * are not safe for concurrent use from multiple threads without external serialization. {@code
 * core-storage}'s own {@link com.p2pchat.storage.SqliteDatabase} gets away without any such
 * serialization only because {@code node-daemon}'s {@code SessionManager} already confines all
 * storage access to its own single {@code inboundExecutor} one layer up — there is no equivalent
 * single caller here, so this class has to provide the same guarantee itself rather than assume
 * it's already given.
 *
 * <p>This also happens to solve the other half of the same problem: keeping actual disk I/O
 * completely off the Netty event-loop threads that would otherwise stall while a write completes,
 * delaying every other connection that same event loop is handling.
 *
 * <p>Track A-3's later periodic TTL-purge sweep is meant to run on this exact same executor too
 * (not a second one), so a sweep can never race a concurrent enqueue/fetch/delete — not wired up
 * yet, since {@link #purgeExpired} doesn't exist yet; noted here so the next change to this class
 * doesn't reach for a separate scheduler without first checking this reasoning.
 */
public final class SqliteRelaySpool implements RelaySpool, AutoCloseable {

    private final Connection connection;
    private final ExecutorService spoolExecutor;

    public SqliteRelaySpool(Connection connection) {
        this.connection = connection;
        this.spoolExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "relay-spool");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<Void> enqueue(String targetPeerId, String senderPeerId, byte[] payload) {
        Objects.requireNonNull(targetPeerId, "targetPeerId must not be null");
        Objects.requireNonNull(senderPeerId, "senderPeerId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] payloadCopy = payload.clone();
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO spooled_messages (target_peer_id, sender_peer_id, payload, enqueued_at) " +
                            "VALUES (?, ?, ?, ?)")) {
                statement.setString(1, targetPeerId);
                statement.setString(2, senderPeerId);
                statement.setBytes(3, payloadCopy);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to enqueue spooled message for " + targetPeerId, e);
            }
        }, spoolExecutor);
    }

    @Override
    public CompletableFuture<List<SpooledMessage>> fetchForTarget(String targetPeerId) {
        Objects.requireNonNull(targetPeerId, "targetPeerId must not be null");
        return CompletableFuture.supplyAsync(() -> {
            List<SpooledMessage> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, sender_peer_id, payload, enqueued_at FROM spooled_messages " +
                            "WHERE target_peer_id = ? ORDER BY id ASC")) {
                statement.setString(1, targetPeerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        results.add(new SpooledMessage(
                                resultSet.getLong("id"),
                                resultSet.getString("sender_peer_id"),
                                resultSet.getBytes("payload"),
                                Instant.ofEpochMilli(resultSet.getLong("enqueued_at"))));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to fetch spooled messages for " + targetPeerId, e);
            }
            return results;
        }, spoolExecutor);
    }

    @Override
    public CompletableFuture<Void> delete(long id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM spooled_messages WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete spooled message " + id, e);
            }
        }, spoolExecutor);
    }

    /**
     * Shuts down the dedicated executor, waiting briefly for in-flight tasks to complete before
     * forcing termination. Does not close the underlying {@link Connection} — matching
     * {@link com.p2pchat.storage.SqliteDatabase}'s own convention that the connection's owner
     * (whoever opened it) is responsible for closing it, not whoever was merely handed it.
     * Waiting ensures that when this method returns, no background task is actively executing
     * statements against the connection, preventing races if the caller immediately closes it.
     *
     * <p><b>Warning:</b> Must not be called from a task running on {@code spoolExecutor} itself,
     * as {@code awaitTermination} would block the single executor thread waiting on itself,
     * causing a permanent deadlock.
     */
    @Override
    public void close() {
        spoolExecutor.shutdown();
        try {
            if (!spoolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                spoolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            spoolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
