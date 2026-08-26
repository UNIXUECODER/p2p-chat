package com.p2pchat.discovery;

import java.util.List;
import java.util.Objects;

/**
 * The unsigned content of a discovery record: what a peer publishes about itself so other
 * peers can reach it and start a session with it.
 *
 * <p>Deliberately carries no self-claimed peer ID field. The embedded Ed25519 public key on
 * {@link SignedDiscoveryRecord} <i>is</i> the identity claim — see that type's javadoc for why
 * a redundant peerId field here would just create an authoritative-vs-claimed ambiguity bug
 * waiting to happen (which one wins if they disagree?).
 *
 * @param addresses      dialable multiaddrs for this peer, e.g. {@code "/ip4/.../tcp/9000/p2p/<peerid>"}
 * @param preKeyBundle   opaque, {@code PreKeyBundleCodec}-encoded bytes (core-crypto) for
 *                       establishing a new Signal session with this peer; null if not published
 * @param relayMultiaddr this peer's preferred relay, if it has one; null otherwise
 * @param expiresAt      epoch-millis after which this record should be treated as stale
 */
public record DiscoveryRecord(
        List<String> addresses,
        byte[] preKeyBundle,
        String relayMultiaddr,
        long expiresAt
) {
    public DiscoveryRecord {
        Objects.requireNonNull(addresses, "addresses must not be null (use List.of() for none)");
        addresses = List.copyOf(addresses); // defensive + immutable
    }

    public boolean hasPreKeyBundle() {
        return preKeyBundle != null && preKeyBundle.length > 0;
    }

    public boolean hasRelayMultiaddr() {
        return relayMultiaddr != null && !relayMultiaddr.isEmpty();
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAt;
    }
}
