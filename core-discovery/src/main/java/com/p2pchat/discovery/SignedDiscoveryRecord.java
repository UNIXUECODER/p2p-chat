package com.p2pchat.discovery;

import java.security.Signature;

/**
 * A {@link DiscoveryRecord} plus the raw Ed25519 public key and signature that vouch for it —
 * decoded off the wire but not yet trusted. {@link DiscoveryRecordCodec#decodeUnverified}
 * returns this; callers should normally reach for
 * {@link DiscoveryRecordCodec#verifyAndDecode} instead of using this type directly, since
 * decoding successfully only means the bytes were well-formed, not that they're authentic.
 *
 * <p>The embedded public key is the actual identity claim: {@link #derivedPeerId()}
 * recomputes the libp2p peer ID that key implies, and the caller compares it against the peer
 * ID it was actually looking up. This is what prevents a relay — malicious, compromised, or
 * merely careless — from substituting a different peer's (or its own) pre-key bundle into a
 * lookup response: it can forge any {@link DiscoveryRecord} content it likes, but it cannot
 * forge a signature that verifies under a public key whose derived peer ID matches someone
 * else's, without that someone else's private key. This is also why signing this way needs no
 * separate certificate authority or prior key exchange — a libp2p peer ID for an Ed25519 key
 * *is*, by construction, a hash of that same key, so "does this key belong to this peer ID" is
 * arithmetic, not a trust question.
 */
public record SignedDiscoveryRecord(DiscoveryRecord record, byte[] publicKey, byte[] signature) {

    public String derivedPeerId() {
        return Ed25519RecordKeys.peerIdFromRawPublicKey(publicKey);
    }

    /** Verifies only the signature, over this record's canonical unsigned encoding — no peer-ID or expiry check. */
    public boolean verifySignature() {
        try {
            byte[] canonical = DiscoveryRecordCodec.encodeCanonicalUnsigned(record);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(Ed25519RecordKeys.toPublicKey(publicKey));
            verifier.update(canonical);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
