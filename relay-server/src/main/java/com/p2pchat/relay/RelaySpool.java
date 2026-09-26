package com.p2pchat.relay;

import com.p2pchat.network.RelayController;

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
 * delete. (Track A-3's later quota/TTL/max-size enforcement is a separate, later concern from
 * this basic enqueue/fetch/delete shape — not yet part of this interface.)
 */
public interface RelaySpool {

    /**
     * Queues {@code payload} for delivery to {@code targetPeerId} once it next reconnects,
     * attributed to {@code senderPeerId} so the eventual {@code DELIVER} frame ({@link
     * RelayRegistry}'s own job to construct, not this class's) carries the real original sender,
     * not this relay's own identity — the same distinction Track A-2 already had to get right on
     * the client side, for the same underlying reason.
     *
     * @throws NullPointerException if any argument is null
     */
    CompletableFuture<Void> enqueue(String targetPeerId, String senderPeerId, byte[] payload);

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
}
