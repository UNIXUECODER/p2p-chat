package com.p2pchat.model;

import java.util.Objects;

/**
 * The single canonical Java type for "a peer's identifier," introduced in M3d to replace the
 * mix of raw {@code String} and {@code io.libp2p.core.PeerId} that different modules used to
 * pass around independently. See docs/architecture-spec.md §4, which already specified this
 * exact shape ({@code record PeerId(String value)}) before any module needed it enough to
 * justify scaffolding it.
 *
 * <p><b>This unifies the TYPE, not the VALUE SPACE.</b> core-identity's {@code Identity.peerId()}
 * and the network-layer peer ID that core-network / relay-server / node-daemon use for dialing,
 * discovery, and relaying are still two <i>different values</i> derived from the same Ed25519
 * key via two different encodings (SHA-256 hex vs. libp2p's base58 multihash) — that divergence
 * was already documented in the M1.5 README section, and this type does not resolve it.
 *
 * <p>Making core-identity compute the real libp2p-style peer ID would require either
 * reimplementing libp2p's exact multihash/protobuf encoding by hand (a real risk of a subtle,
 * hard-to-verify mismatch against what jvm-libp2p itself computes internally from the same
 * seed — and not something that could be safely done without the ability to compile and run
 * against the real library) or giving core-identity a dependency on jvm-libp2p, which M0
 * deliberately avoided. Both are explicitly deferred, not forgotten.
 *
 * <p>What this type DOES fix: every module boundary that used to pass a peer identifier now
 * agrees on one Java type. That's what let {@code io.libp2p.core.PeerId} stop leaking through
 * core-network's public API (see core-network's OnEnvelopeMessage / RelayEventHandler /
 * DiscoveryRequestHandler, and the corresponding {@code api} → {@code implementation} change
 * for jvm-libp2p in core-network's build.gradle.kts), and it's what lets core-storage give
 * {@code peer_id} columns a real type instead of a bare String.
 */
public record PeerId(String value) {

    public PeerId {
        Objects.requireNonNull(value, "PeerId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PeerId value must not be blank");
        }
    }

    /** Equivalent to {@code new PeerId(value)}; reads better at call sites converting an existing string. */
    public static PeerId of(String value) {
        return new PeerId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
