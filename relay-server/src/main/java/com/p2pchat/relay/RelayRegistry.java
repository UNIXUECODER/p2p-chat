package com.p2pchat.relay;

import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.model.PeerId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The relay server's actual logic: remembers which peer is on which live
 * connection (via onConnected, fired by RelayProtocol the moment a stream
 * activates — no separate REGISTER message needed, the connection itself
 * IS the registration), and forwards FORWARD-type frames to their target's
 * connection, relabeled as a DELIVER-type frame carrying the original sender.
 */
public class RelayRegistry implements RelayEventHandler {

    private final Map<String, RelayController> registered = new ConcurrentHashMap<>();

    @Override
    public void onConnected(PeerId peerId, RelayController controller) {
        registered.put(peerId.toString(), controller);
        System.out.println("[relay] registered: " + peerId + " (" + registered.size() + " peer(s) connected)");
    }

    @Override
    public void onFrame(PeerId sender, RelayFrame frame) {
        if (!frame.isForwardRequest()) {
            // A relay server should only ever RECEIVE forward requests — only clients
            // receive deliveries. Ignore defensively rather than fail loudly on a
            // message shape that should never reach this side of the protocol.
            return;
        }

        RelayController target = registered.get(frame.peerId());
        if (target == null) {
            System.out.println("[relay] no route to " + frame.peerId() + " (not currently connected) — dropped");
            return;
        }

        target.send(new RelayFrame(false, sender.toString(), frame.payload()));
        System.out.println("[relay] forwarded " + frame.payload().length + " bytes from " + sender + " to " + frame.peerId());
    }
}
