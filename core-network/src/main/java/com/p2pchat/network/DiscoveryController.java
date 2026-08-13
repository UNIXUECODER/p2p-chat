package com.p2pchat.network;

import java.util.concurrent.CompletableFuture;

/**
 * Returned by connectToDiscovery(). publish() is fire-and-forget (the
 * server just stores it, no response expected). lookup() is genuine
 * request/response, correlated via the returned future itself rather than
 * an external callback — unlike RelayController, no separate event handler
 * interface is needed on the client side for this.
 */
public interface DiscoveryController {
    void publish(byte[] payload);

    CompletableFuture<DiscoveryLookupResult> lookup(String targetPeerId);
}
