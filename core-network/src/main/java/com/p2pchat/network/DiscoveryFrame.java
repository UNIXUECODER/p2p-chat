package com.p2pchat.network;

/**
 * A single discovery message. For PUBLISH, peerId is unused (the publisher's
 * identity comes from the connection itself, same insight already used in
 * RelayRegistry). For LOOKUP, peerId is the target being queried. For both
 * LOOKUP_RESPONSE_* kinds, peerId echoes back which peer the response is about.
 */
public record DiscoveryFrame(DiscoveryMessageType type, String peerId, byte[] payload) {
}
