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
 */
public class ConnectionStrategy {

    private final PeerNetworkService network;
    private final long directTimeoutMillis;

    public ConnectionStrategy(PeerNetworkService network, long directTimeoutMillis) {
        this.network = network;
        this.directTimeoutMillis = directTimeoutMillis;
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

        if (relayMultiaddr != null && targetPeerId != null) {
            try {
                RelayController relay = network.connectToRelay(relayMultiaddr, new RelayEventHandler() {
                    @Override
                    public void onConnected(PeerId peerId, RelayController controller) {
                    }

                    @Override
                    public void onFrame(PeerId sender, RelayFrame frame) {
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
