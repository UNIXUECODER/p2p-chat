package com.p2pchat.daemon.session;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.TransferState;

/**
 * M6g-3 (§2.5): what {@link SessionManager} calls when something happens that the outside world
 * — eventually the JSON-RPC layer (M6g-4), pushing {@code event.*} frames over WebSocket — needs
 * to know about. A plain listener interface, not a generic event bus: this project has exactly
 * one producer ({@code SessionManager}) and, for now, exactly one real consumer (the RPC layer),
 * and a bus would be abstraction without the second use case that would justify it — the same
 * reasoning that kept {@code ChatWireMessage} and {@code FileTransferMessage} as separate
 * hierarchies until {@code ApplicationMessageRouter} actually needed both.
 *
 * <p><b>Default no-op methods</b>, matching {@link FileTransferHandler}'s own established
 * convention — a caller that only cares about some events (or, as {@link #NONE} below, none at
 * all) implements only what it needs, and {@code SessionManager} never has to null-check before
 * calling.
 *
 * <p><b>Every method here is called off {@code SessionManager}'s inbound-processing thread</b> —
 * see that class's own Javadoc for {@code inboundExecutor}'s one, narrow, load-bearing job:
 * serializing the dedup check-then-insert race for concurrent senders. A listener implementation
 * is under no obligation to be fast (a real one will eventually do WebSocket I/O, writing to
 * potentially many open connections), and letting it run on that same thread would mean a slow or
 * stuck listener call blocks message processing for every other peer, for a reason that has
 * nothing to do with what {@code inboundExecutor} actually exists to protect. {@code
 * SessionManager} dispatches these on its own separate {@code eventExecutor} for exactly this
 * reason — a deliberate decision, not an oversight discovered later, made at the same time this
 * interface itself was designed.
 *
 * <p><b>{@link #onNetworkStatusChanged()} deliberately takes no parameters</b>, unlike the
 * original plan's sketch (which left its shape as "TBD, see §2.4"). {@code SessionManager} alone
 * cannot construct the full {@code network.status} payload §2.4 defines — that needs {@code
 * PeerRoutingTable} data too (M6g-2), and {@code SessionManager} was deliberately kept unaware of
 * {@code PeerRoutingTable} entirely (see that class's own Javadoc on why: session-liveness and
 * routing data are two independently-sourced facts, joined by whoever needs both, not baked
 * together prematurely). Giving this callback a half-populated payload would be worse than giving
 * it none — it would look complete while quietly omitting fields only a caller with routing-table
 * access could fill in correctly. This fires as a bare "something worth re-checking just
 * happened" signal; the real payload is the M6g-4 caller's own job to (re)build, by combining
 * this signal with a fresh {@code PeerRoutingTable.list()} and {@code SessionManager
 * .hasSession(...)} query — which §2.4 already established doesn't need a push mechanism to stay
 * correct, since there's no live presence/heartbeat protocol underneath it to begin with.
 */
public interface DaemonEventListener {

    /** Fires once, only for a genuinely new (non-duplicate) message, after it's durably persisted — never before. */
    default void onMessageReceived(Message message) {
    }

    /** Fires when a delivery receipt updates a previously-sent message's own recorded state. */
    default void onDeliveryStateChanged(String messageId, DeliveryState newState) {
    }

    /** Fires once per incoming file offer, before any accept/reject decision has been made. */
    default void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize) {
    }

    /** Fires once per chunk received on the receiving side of a transfer — see {@code DefaultFileTransferHandler} for why this doesn't also cover the sending side. */
    default void onFileTransferProgress(String transferId, int chunksReceived, int totalChunks, TransferState state) {
    }

    /** Fires when a Signal session with a peer transitions from not-existing to existing — see this interface's own Javadoc for why the payload is deliberately empty. */
    default void onNetworkStatusChanged() {
    }

    /** A listener that does nothing — for callers (tests, or a daemon run with no RPC layer attached yet) that don't need one. */
    DaemonEventListener NONE = new DaemonEventListener() {
    };
}
