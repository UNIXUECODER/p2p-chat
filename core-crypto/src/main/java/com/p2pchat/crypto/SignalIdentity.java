package com.p2pchat.crypto;

import org.signal.libsignal.protocol.IdentityKeyPair;

/**
 * The dedicated Signal Protocol identity for this node — deliberately SEPARATE
 * from the libp2p network identity (core-identity's Ed25519 key). Signal's
 * IdentityKeyPair uses Curve25519 (X25519-family) keys, a different curve
 * representation than pure Ed25519 — unlike M1.5's network identity, there's
 * no safe direct conversion between the two, so this is its own identity by
 * design, not an oversight. See docs/architecture-spec.md §8 amendment.
 */
public record SignalIdentity(IdentityKeyPair keyPair, int registrationId) {
}
