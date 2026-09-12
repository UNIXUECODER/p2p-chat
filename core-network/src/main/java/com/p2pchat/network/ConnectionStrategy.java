package com.p2pchat.network;

import com.p2pchat.model.PeerId;

/**
 * M3b: tries a direct connection first, falling back to a relay only if
 * that fails or times out — built entirely on primitives already proven
 * working (sendEnvelope from M2b, connectToRelay from M3a). No new
 * libp2p-specific protocol code needed here, which is why this milestone
 * genuinely needed less external verification than M2c or M3a: the pieces
 * being orchestrated are already known-correct, this is just the decision
 * logic on top of them.
 *
 * Always returns a status rather than sometimes throwing — a failure on
 * both paths returns UNREACHABLE rather than propagating an exception, so
 * a caller always gets a definitive answer to act on, never silent
 * ambiguity about what actually happened.
 *
 * <p><b>A-1 update (pre-m6h-hardening-plan.md, Track A):</b> the relay leg used to dial a brand
 * new connection per send and never close it (finding A-2) — real for every send, and a genuine
 * connection leak. The 3-arg constructor below adds an optional {@link RelaySession}: when
 * present, the relay leg reuses that persistent, already-reconnecting connection instead of
 * dialing its own. The original 2-arg constructor is untouched and behaves exactly as before —
 * every M3b/M6b call site that hasn't been wired to a {@code RelaySession} yet keeps the old
 * per-send-dial behaviour, not a "different, unproven path." {@link #send}'s own signature hasn't
 * changed at all: {@code relayMultiaddr} is still accepted (and still used by the legacy path),
 * just no longer needed to locate the connection when a session-backed instance is in play.
 */
public class ConnectionStrategy {

    private final PeerNetworkService network;
    private final long directTimeoutMillis;
    private final RelaySession relaySession;

    public ConnectionStrategy(PeerNetworkService network, long directTimeoutMillis) {
        this(network, directTimeoutMillis, null);
    }

    public ConnectionStrategy(PeerNetworkService network, long directTimeoutMillis, RelaySession relaySession) {
        this.network = network;
        this.directTimeoutMillis = directTimeoutMillis;
        this.relaySession = relaySession;
    }

    /**
     * Attempts to reach a peer: direct first (if directMultiaddr is non-null
     * and non-blank), bounded by directTimeoutMillis, falling back to the
     * given relay if direct isn't available, fails, or times out. Pass null
     * (or a blank string) for directMultiaddr to skip straight to relay —
     * useful once a peer's direct reachability is already known to be poor.
     */
    public ConnectivityStatus send(String directMultiaddr, String relayMultiaddr, String targetPeerId, byte[] data) {
        if (directMultiaddr != null && !directMultiaddr.isBlank()) {
            try {
                network.sendEnvelope(directMultiaddr, data, directTimeoutMillis);
                return ConnectivityStatus.DIRECT;
            } catch (Exception directFailure) {
                // Direct didn't work — fall through to the relay attempt below,
                // deliberately not distinguishing "timed out" from "refused" from
                // "malformed address" here. A real production system would want
                // that distinction (to avoid retrying a malformed address forever,
                // for instance); scoped out of M3b as a known simplification.
            }
        }

        if (relaySession != null && targetPeerId != null) {
            // A-1 path: reuse the persistent session rather than dialing. relayMultiaddr is
            // intentionally not consulted here — the session was already configured with its own
            // relay address at construction time (see RelaySession's own Javadoc); accepting a
            // second, possibly-different address per call isn't a case this project has an actual
            // use for yet (A-4 territory if it ever becomes one), and silently ignoring a mismatch
            // is preferable to guessing which one the caller actually meant.
            return relaySession.send(targetPeerId, data) ? ConnectivityStatus.RELAYED : ConnectivityStatus.UNREACHABLE;
        }

        if (relayMultiaddr != null && targetPeerId != null) {
            // Legacy path (pre-A-1): dial-per-send, exactly as before -- kept for any caller not
            // yet constructed with a RelaySession, not a fallback for when the session fails.
            // Deliberately still doesn't call relay.close() despite RelayController now having
            // one: every existing verified caller of this path (ReachPeerMain, RelayForwardMain,
            // OutboundMessageServiceTest's relay-fallback case) relies on writeAndFlush's write
            // actually reaching the wire before teardown -- see those demo Mains' own
            // Thread.sleep(500) comments for the exact race this would reopen if closed
            // immediately after send() with no such wait. RelaySession is the real fix (the
            // connection is kept, never per-send, so this race can't occur there); patching this
            // path safely would need the same care and isn't worth the risk to already-verified
            // code for a path this milestone is actively migrating callers off of.
            try {
                RelayController relay = network.connectToRelay(relayMultiaddr, new RelayEventHandler() {
                    @Override
                    public void onConnected(PeerId peerId, RelayController controller) {
                    }

                    @Override
                    public void onFrame(PeerId sender, RelayFrame frame) {
                    }

                    @Override
                    public void onDisconnected(PeerId peerId, RelayController controller) {
                    }
                });
                relay.send(new RelayFrame(true, targetPeerId, data));
                return ConnectivityStatus.RELAYED;
            } catch (Exception relayFailure) {
                return ConnectivityStatus.UNREACHABLE;
            }
        }

        return ConnectivityStatus.UNREACHABLE;
    }
}
