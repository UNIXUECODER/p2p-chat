package com.p2pchat.network;

/**
 * How a message actually reached (or failed to reach) a peer — surfaced
 * explicitly by ConnectionStrategy rather than left implicit, per the
 * "always surfaced, never silently assumed" principle from
 * docs/architecture-spec.md §10.
 */
public enum ConnectivityStatus {
    DIRECT,
    RELAYED,
    UNREACHABLE
}
