package com.p2pchat.network;

import com.p2pchat.model.PeerId;

/**
 * Two callbacks, not one — this is NOT a functional interface, so it cannot
 * be implemented with a lambda; use an (anonymous) class. Deliberately two
 * methods: relay connections are long-lived (unlike Envelope's one-shot
 * dial/send/disconnect), so the controller returned when a connection
 * activates needs to be captured for reuse later — onConnected is that
 * capture point, separate from onFrame handling actual messages.
 */
public interface RelayEventHandler {

    /**
     * Fires once, right when a peer's Relay-protocol connection to us becomes
     * active — whether we dialed them or they dialed us. The controller is
     * how WE send frames back to them later, for as long as this connection stays open.
     */
    void onConnected(PeerId peerId, RelayController controller);

    /** Fires whenever a RelayFrame arrives on this connection. */
    void onFrame(PeerId sender, RelayFrame frame);

    /**
     * A-1 (pre-m6h-hardening-plan.md, Track A): fires once when this connection closes, whichever
     * side initiated it or however it happened (clean close, crash, network loss). Before this,
     * {@code RelayProtocol}'s {@code onClosed}/{@code onReadClosed} were literal no-ops — a caller
     * holding a controller from {@link #onConnected} had no way to learn it was dead except a
     * later {@code send()} throwing. {@link RelaySession} is the reason this exists: it can't
     * reconnect promptly if it doesn't know it disconnected.
     *
     * <p>Takes {@code controller} — the exact instance that closed — for the same reason {@link
     * #onConnected} does: a caller tracking one connection per peer (like {@code
     * relay-server}'s own registry) needs to tell "the connection I have on file for this peer
     * just died" apart from "a stale signal arrived late for a connection I've since replaced with
     * a fresher one from a fast reconnect." Removing an entry keyed on {@code peerId} alone,
     * without checking it's still this exact controller, could otherwise evict a peer's brand-new
     * connection because of a late signal from its old one.
     */
    void onDisconnected(PeerId peerId, RelayController controller);
}
