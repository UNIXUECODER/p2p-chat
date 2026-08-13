package com.p2pchat.identity;

public interface IdentityService {
    Identity createIdentity(String displayName);
    Identity loadIdentity() throws IdentityNotFoundException;
    boolean hasIdentity();

    /**
     * The raw 32-byte Ed25519 private key seed (RFC 8032 format) backing this
     * identity. core-network uses this to derive a stable libp2p peer identity
     * from the SAME keypair, instead of a random one per process. Returns raw
     * bytes rather than a libp2p-specific type, so core-identity doesn't need
     * to depend on the networking library.
     */
    byte[] rawPrivateKeySeed();
}
