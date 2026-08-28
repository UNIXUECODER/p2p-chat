package com.p2pchat.daemon.routing;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.PeerRoute;

import java.util.List;

/**
 * M6g-2: the peer-resolution layer {@code messages.send} and {@code contacts.add} need — maps a
 * {@link PeerId} to how this daemon can reach and message them, so an RPC caller only has to
 * supply a peer ID, not raw multiaddrs. Exactly the class {@code
 * docs/M6g-gap-analysis-and-plan.md §2.3} describes; what moved is only the data ({@link
 * PeerRoute} itself now lives in {@code core-storage.model} — see that record's own Javadoc for
 * why), not this class's role or location.
 *
 * <p><b>Deliberately no in-memory cache on top of {@link StorageService}.</b> The plan's original
 * sketch described this as "in-memory + persistent." On reflection that's a real, self-inflicted
 * correctness risk for no proven need: a cache introduces a second copy of this data that can
 * drift from what's on disk, and every write here already goes through a real SQLite database
 * that's fast enough for what calls this — a route lookup per message send or contact add, not a
 * per-millisecond hot loop. This is the same "don't add complexity ahead of a proven need"
 * instinct this project has applied consistently elsewhere (see {@code
 * docs/M6g-gap-analysis-and-plan.md §2.5} declining a generic event bus for the identical reason).
 * If a real performance need shows up later, add the cache then, with the invalidation strategy
 * that need actually demands — not preemptively now.
 *
 * <p><b>Deliberately no session-awareness.</b> {@code §2.4}'s "connected" definition needs a
 * peer's route AND whether a Signal session exists with them, but this class only ever answers
 * the first half. {@code SessionManager.hasSession(PeerId)} already answers the second half and
 * is the actual source of truth for it (see that method's own Javadoc) — duplicating that check
 * in here would mean either giving this class a dependency on {@code SignalProtocolStore} it
 * has no other reason to need, or silently trusting a second, potentially-stale copy of the same
 * fact. The future caller that needs both together (M6g-4's {@code network.connectedPeers}) is
 * expected to combine {@link #list()} with {@code SessionManager.hasSession(...)} itself, the
 * same way any two independent facts get combined by whoever needs both, rather than baking that
 * join into this class ahead of a second real caller needing it.
 */
public final class PeerRoutingTable {

    private final StorageService storage;

    public PeerRoutingTable(StorageService storage) {
        this.storage = storage;
    }

    /**
     * Records (or merges into) what this daemon knows about {@code observed.peerId()}. See
     * {@link StorageService#upsertPeerRoute} for the exact merge semantics — a {@code null}
     * field on {@code observed} preserves whatever was already known, not overwrite it. Callers
     * across this milestone and future ones (discovery lookups, {@code contacts.add}, and later
     * an inbound message's {@code senderAddress} once {@code SessionManager} is wired for it in
     * M6g-3) all just construct a {@link PeerRoute} with whichever fields they actually learned
     * and leave the rest {@code null} — deliberately one general method here rather than a named
     * wrapper per call site, since this milestone has exactly one real caller
     * ({@code ContactService}) and inventing named variants for callers that don't exist yet
     * would be exactly the kind of speculative abstraction {@code §2.5} already argued against
     * for the event-bus question.
     *
     * @return the fully-merged row, as {@link StorageService#upsertPeerRoute} already returns it.
     */
    public PeerRoute upsert(PeerRoute observed) {
        return storage.upsertPeerRoute(observed);
    }

    /** The current best-known route to {@code peerId}, or {@code null} if this daemon has never observed one. */
    public PeerRoute get(PeerId peerId) {
        return storage.getPeerRoute(peerId);
    }

    /** Every known route, most-recently-observed first. */
    public List<PeerRoute> list() {
        return storage.listPeerRoutes();
    }
}
