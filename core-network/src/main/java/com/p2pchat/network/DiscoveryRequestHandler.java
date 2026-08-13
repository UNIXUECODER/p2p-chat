package com.p2pchat.network;

import com.p2pchat.model.PeerId;

/** Only the discovery-serving side (relay-server) implements this for real. */
public interface DiscoveryRequestHandler {
    /** Called when a peer publishes their record. No response is sent back. */
    void onPublish(PeerId publisher, byte[] payload);

    /** Called when a peer looks up another peer's record. Return null if nothing is on file. */
    byte[] onLookup(String targetPeerId);
}
