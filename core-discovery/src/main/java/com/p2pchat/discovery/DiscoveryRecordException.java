package com.p2pchat.discovery;

/**
 * Thrown by {@link DiscoveryRecordCodec#verifyAndDecode} when a wire payload fails to produce
 * a trustworthy {@link DiscoveryRecord} — never ambiguous about which of the four ways it
 * failed, matching the "explicit result, never ambiguous" convention
 * {@code DiscoveryLookupResult} already established in core-network. {@code MALFORMED} is also
 * the exact name already reserved for this failure mode in the M6g RPC error vocabulary (see
 * {@code docs/M6-roadmap-and-decisions.md}) — decode failures here are expected to surface
 * through it unchanged, not get remapped to a different string later.
 */
public class DiscoveryRecordException extends Exception {

    public enum Reason {
        /** Wire bytes didn't parse as a DiscoveryRecordV2 at all — wrong marker byte, truncated, or corrupt. */
        MALFORMED,
        /** Parsed cleanly, but the signature doesn't verify against the embedded public key. */
        BAD_SIGNATURE,
        /** Signature is valid, but the embedded public key's derived peer ID isn't the one being looked up. */
        PEER_ID_MISMATCH,
        /** Signature and peer ID both check out, but expiresAt has already passed. */
        EXPIRED
    }

    private final Reason reason;

    public DiscoveryRecordException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DiscoveryRecordException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
