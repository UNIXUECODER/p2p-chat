package com.p2pchat.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * <p><b>Track A-3, Commit 3:</b> the executor is now a {@link ScheduledExecutorService}
 * specifically so {@link #startPeriodicPurge} can run on this exact same thread as every other
 * operation, rather than a second one — a sweep can never race a concurrent enqueue/fetch/delete
 * because nothing here ever runs concurrently with anything else in this class, by construction.
 */
public final class SqliteRelaySpool implements RelaySpool, AutoCloseable {

    /** pre-m6h-hardening-plan.md's own suggested figure; see {@link RelaySpool} for why. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    public static final int DEFAULT_MAX_MESSAGES_PER_PEER = 500;

    public static final long DEFAULT_MAX_BYTES_PER_PEER = 50L * 1024 * 1024;

    /** Track A-3 Decision 1's hard backstop — see {@link RelaySpool#enqueue}'s own Javadoc. */
    public static final int DEFAULT_MAX_FRAME_SIZE_BYTES = 64 * 1024;

    private final Connection connection;
    private final ScheduledExecutorService spoolExecutor;
    private final Duration ttl;
    private final int maxMessagesPerPeer;
    private final long maxBytesPerPeer;
    private final int maxFrameSizeBytes;

    public SqliteRelaySpool(Connection connection) {
        this(connection, DEFAULT_TTL, DEFAULT_MAX_MESSAGES_PER_PEER, DEFAULT_MAX_BYTES_PER_PEER,
                DEFAULT_MAX_FRAME_SIZE_BYTES);
    }

    /**
     * @param ttl               how long a message may sit spooled before {@link #purgeExpired}
     *                          considers it eligible for removal
     * @param maxMessagesPerPeer per-target-peer cap on queued message count
     * @param maxBytesPerPeer    per-target-peer cap on total queued payload bytes
     * @param maxFrameSizeBytes  hard ceiling on any single message's payload size, checked before
     *                           the quota and before this even reaches the database
     */
    public SqliteRelaySpool(Connection connection, Duration ttl, int maxMessagesPerPeer,
                             long maxBytesPerPeer, int maxFrameSizeBytes) {
        this.connection = connection;
        this.ttl = ttl;
        this.maxMessagesPerPeer = maxMessagesPerPeer;
        this.maxBytesPerPeer = maxBytesPerPeer;
        this.maxFrameSizeBytes = maxFrameSizeBytes;
        this.spoolExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "relay-spool");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<EnqueueResult> enqueue(String targetPeerId, String senderPeerId, byte[] payload) {
        Objects.requireNonNull(targetPeerId, "targetPeerId must not be null");
        Objects.requireNonNull(senderPeerId, "senderPeerId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length > maxFrameSizeBytes) {
            return CompletableFuture.completedFuture(EnqueueResult.REJECTED_MESSAGE_TOO_LARGE);
        }
        byte[] payloadCopy = payload.clone();
        return CompletableFuture.supplyAsync(() -> {
            try {
                long[] usage = currentUsageFor(targetPeerId);
                long currentCount = usage[0];
                long currentBytes = usage[1];
                if (currentCount >= maxMessagesPerPeer || currentBytes + payloadCopy.length > maxBytesPerPeer) {
                    return EnqueueResult.REJECTED_QUOTA_EXCEEDED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO spooled_messages (target_peer_id, sender_peer_id, payload, enqueued_at) " +
                                "VALUES (?, ?, ?, ?)")) {
                    statement.setString(1, targetPeerId);
                    statement.setString(2, senderPeerId);
                    statement.setBytes(3, payloadCopy);
                    statement.setLong(4, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                return EnqueueResult.ACCEPTED;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to enqueue spooled message for " + targetPeerId, e);
            }
        }, spoolExecutor);
    }

    /**
     * @return {@code {messageCount, totalPayloadBytes}} currently queued for {@code targetPeerId}
     */
    private long[] currentUsageFor(String targetPeerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) AS msg_count, COALESCE(SUM(LENGTH(payload)), 0) AS total_bytes " +
                        "FROM spooled_messages WHERE target_peer_id = ?")) {
            statement.setString(1, targetPeerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new long[]{resultSet.getLong("msg_count"), resultSet.getLong("total_bytes")};
            }
        }
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

    @Override
    public CompletableFuture<Integer> purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return CompletableFuture.supplyAsync(() -> purgeExpiredSync(now), spoolExecutor);
    }

    private int purgeExpiredSync(Instant now) {
        long cutoffMillis = now.minus(ttl).toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM spooled_messages WHERE enqueued_at < ?")) {
            statement.setLong(1, cutoffMillis);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge expired spooled messages", e);
        }
    }

    /**
     * Starts a periodic sweep calling {@link #purgeExpired} with the current time, every {@code
     * interval}, on this same instance's dedicated executor (never a separate one — see this
     * class's own Javadoc). Not started automatically by either constructor: a test constructing
     * a {@link SqliteRelaySpool} to call {@link #purgeExpired} directly and deterministically (the
     * whole reason that method takes {@code now} as a parameter — see {@link RelaySpool}'s own
     * Javadoc) shouldn't also get an uncontrolled background timer it never asked for. Wiring this
     * into the real relay process — with a real interval, e.g. hourly — is {@code
     * RelayServerMain}'s job, not this constructor's.
     *
     * <p>The scheduled task calls {@link #purgeExpiredSync}, the private helper, directly —
     * <b>never {@link #purgeExpired} itself.</b> This mattered enough to get wrong once already:
     * the scheduled lambda below already runs on {@code spoolExecutor}; calling the public {@link
     * #purgeExpired}, which submits a second task to that same single-threaded executor and then
     * blocks (via {@code .join()}) waiting for it, is a guaranteed self-deadlock — a single-thread
     * executor has no second thread free to ever run the task it's blocking on. Caught only by
     * actually running this against real SQLite, not by reading it; left as a large comment
     * because "just call the existing public method" is exactly the refactor a future edit will
     * be tempted to make back.
     *
     * <p>The task also swallows every exception itself rather than letting one propagate: {@link
     * ScheduledExecutorService#scheduleAtFixedRate} silently and permanently cancels all future
     * executions of a task that ever throws, with no exception surfaced anywhere — the kind of
     * failure mode that's easy to never notice until months of unpurged messages later. Logging
     * and continuing is deliberately safer here than letting that happen.
     */
    public void startPeriodicPurge(Duration interval) {
        long intervalMillis = interval.toMillis();
        spoolExecutor.scheduleAtFixedRate(() -> {
            try {
                int purged = purgeExpiredSync(Instant.now());
                if (purged > 0) {
                    System.out.println("[relay-spool] periodic purge removed " + purged + " expired message(s)");
                }
            } catch (Exception e) {
                System.err.println("[relay-spool] periodic purge failed, will retry next interval: " + e);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
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

