package com.p2pchat.relay;

import com.p2pchat.network.RelayController;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Track A-3 (pre-m6h-hardening-plan.md): durable, store-and-forward queue for a {@code FORWARD}
 * frame the relay can't deliver immediately because its target isn't currently connected. An
 * interface, not just {@link SqliteRelaySpool} directly, for the same reason every other
 * storage-backed service in this project (e.g. {@code core-storage}'s own {@code
 * StorageService}) is fronted by one: so {@link RelayRegistry}'s own tests can exercise the
 * enqueue-on-no-route / drain-on-reconnect logic against a fake, without needing a real SQLite
 * file for every test.
 *
 * <p>Every method returns a {@link CompletableFuture} rather than blocking its caller, because
 * every implementation's real work happens off whatever thread calls it — {@link RelayRegistry}'s
 * own callbacks run directly on Netty's I/O worker threads (see that class's own Javadoc), and
 * disk I/O has no business blocking one of those.
 *
 * <p><b>Deliberately three separate primitives, not one "drain" call that fetches and deletes
 * together:</b> {@link RelayController#send} gives no signal at all about whether a write
 * actually reached the wire — confirmed by reading that interface directly, not assumed — so
 * this class cannot know on its own whether a given frame was "delivered enough" to forget. Only
 * the caller, attempting each send in turn, can decide that. Splitting {@link #fetchForTarget}
 * from {@link #delete} lets a caller delete exactly the ones it actually attempted to send, one
 * at a time, so a peer that disconnects mid-flush leaves its remaining, not-yet-attempted
 * messages safely spooled for the next reconnect rather than losing them to an all-or-nothing
 * delete.
 *
 * <p><b>Every method taking a reference-typed argument throws {@link NullPointerException}
 * immediately and synchronously</b> — before any work is submitted to whatever executor an
 * implementation uses internally, not wrapped in the returned {@link CompletableFuture}'s
 * exceptional completion. Deliberate: a fire-and-forget async API whose caller doesn't always
 * inspect the returned future (this interface's own {@link #enqueue}, called from {@link
 * RelayRegistry}, is exactly that shape) should never let a programming error at the call site
 * disappear silently into an unobserved future — failing loudly, synchronously, on the caller's
 * own thread is what actually surfaces the bug.
 */
public interface RelaySpool {

    /**
     * Queues {@code payload} for delivery to {@code targetPeerId} once it next reconnects,
     * attributed to {@code senderPeerId} so the eventual {@code DELIVER} frame ({@link
     * RelayRegistry}'s own job to construct, not this class's) carries the real original sender,
     * not this relay's own identity — the same distinction Track A-2 already had to get right on
     * the client side, for the same underlying reason.
     *
     * <p>Track A-3, Commit 3: may now reject rather than unconditionally accept — see {@link
     * EnqueueResult}. {@code payload} is checked against the max single-frame size first, before
     * anything else (including before the quota check, and before touching the database at all).
     * A real client should never legitimately produce a frame anywhere near that ceiling: chat
     * messages, handshakes, and delivery receipts are all well under a few KB even with PQXDH
     * overhead, and file-transfer chunks — the one thing plausibly large — can only ever be sent
     * in response to an explicit chunk request the sender received from an online peer, so a
     * chunk destined for the spool (an offline target) should never exist in the first place; see
     * pre-m6h-hardening-plan.md's Track A-3 §Decision 1 for the fuller reasoning, confirmed
     * against {@code DefaultFileTransferHandler}'s actual send path directly, not assumed. The
     * ceiling exists as a hard backstop against an anomalous or malicious client, not a limit any
     * correct one should ever approach.
     *
     * @throws NullPointerException if any argument is null
     */
    CompletableFuture<EnqueueResult> enqueue(String targetPeerId, String senderPeerId, byte[] payload);

    /**
     * Every currently-spooled message for {@code targetPeerId}, oldest first. Does not delete
     * anything — see this interface's own Javadoc for why fetch and delete are deliberately
     * separate.
     *
     * @throws NullPointerException if {@code targetPeerId} is null
     */
    CompletableFuture<List<SpooledMessage>> fetchForTarget(String targetPeerId);

    /**
     * Removes one spooled message by id, once a caller has done whatever it needed to with it
     * (typically: attempted delivery via {@link RelayController#send}).
     */
    CompletableFuture<Void> delete(long id);

    /**
     * Permanently removes every message older than this implementation's TTL, as measured from
     * {@code now}. Returns the number of rows purged, so both a periodic background sweep (for a
     * log line) and a test (for a deterministic assertion — see this method's own point) have
     * something to check.
     *
     * <p>Takes {@code now} as a parameter rather than reading the system clock internally
     * specifically so a test can purge deterministically — insert a message, call {@code
     * purgeExpired(insertTime.plus(ttl).plusSeconds(1))}, assert it's gone — without sleeping real
     * wall-clock time to wait out a multi-day TTL.
     */
    CompletableFuture<Integer> purgeExpired(Instant now);
}

