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
}
