package com.p2pchat.storage.model;

import com.p2pchat.model.PeerId;

/**
 * M6g-2: everything this daemon currently knows about reaching and starting a session with a
 * peer — mirrors the {@code peer_routes} table. Lives here in {@code core-storage.model}, not
 * {@code node-daemon} as {@code docs/M6g-gap-analysis-and-plan.md §2.3/§3} first sketched it:
 * this is a persisted row with the exact same status as {@link Contact}/{@link Conversation}
 * (owned by the storage schema, read and written through {@code StorageService} like every other
 * domain record here), not itself a daemon-level orchestration concern. {@code PeerRoutingTable}
 * (node-daemon) is still exactly where the plan put it — the class that composes this data with
 * session-liveness — this record just isn't a good reason to give that class its own competing
 * domain-model package.
 *
 * <p><b>Deliberately excludes {@code hasSession}</b>, even though an earlier draft of the plan
 * (§2.3) listed it as a field here — the plan's own later section (§3) already dropped it, and
 * that's the version this follows: whether a Signal session exists lives in the
 * {@code SignalProtocolStore}, not here, and storing a second, unsynchronized copy of that fact
 * would just create a staleness bug the moment a session is established or torn down without
 * this row being told. {@code PeerRoutingTable} derives it at read time from whoever already
 * knows the real answer ({@code SessionManager.hasSession(PeerId)}), instead.
 *
 * <p><b>{@code preKeyBundle}</b> is opaque, {@code PreKeyBundleCodec}-encoded bytes — the same
 * convention {@code core-discovery}'s {@code DiscoveryRecord.preKeyBundle()} already uses (not
 * linked here: core-storage has no dependency on core-discovery, and this is a convention, not a
 * shared type), and not a coincidence: this is usually populated directly from a verified {@code DiscoveryRecord}'s own
 * bundle field (see {@code ContactService}). Left undecoded deliberately, so that nothing in
 * {@code core-storage} or the peer-routing layer needs a {@code libsignal-client} dependency —
 * only the code that's actually about to establish a session (future {@code SessionManager}
 * wiring) needs to call {@code PreKeyBundleCodec.decode} on it, and only right before it does.
 * This is also what keeps a contact addition from spending the peer's one-time prekey the moment
 * they're added rather than the moment they're actually messaged — see {@code ContactService}'s
 * own javadoc for why that matters given the prekey-exhaustion issue M6e-2 already found.
 *
 * @param peerId          who this route is for — the primary key.
 * @param directMultiaddr this peer's last-known dialable multiaddr, or {@code null} if unknown.
 * @param relayMultiaddr  this peer's last-known preferred relay, or {@code null} if unknown.
 * @param displayName     cosmetic only — from an invite code's {@code n} field or wherever else
 *                        this route was populated from; not the source of truth for a contact's
 *                        name (that's {@link Contact#displayName()}), just a best-effort label
 *                        for peers this daemon knows a route to but may not yet have as a contact.
 * @param preKeyBundle    opaque, undecoded pre-key bundle bytes, or {@code null} if none known.
 * @param lastSeen        epoch-millis of the observation that produced this row's current state.
 */
public record PeerRoute(
        PeerId peerId,
        String directMultiaddr,
        String relayMultiaddr,
        String displayName,
        byte[] preKeyBundle,
        long lastSeen
) {
    public boolean hasDirectMultiaddr() {
        return directMultiaddr != null && !directMultiaddr.isBlank();
    }

    public boolean hasRelayMultiaddr() {
        return relayMultiaddr != null && !relayMultiaddr.isBlank();
    }

    public boolean hasPreKeyBundle() {
        return preKeyBundle != null && preKeyBundle.length > 0;
    }
}
