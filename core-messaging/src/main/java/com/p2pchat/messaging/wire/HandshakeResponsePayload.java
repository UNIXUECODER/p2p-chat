package com.p2pchat.messaging.wire;

import java.util.Objects;
import java.util.Set;

/**
 * The reply to a {@link HandshakeInitPayload} — same shape, same validation, different direction.
 * A separate record rather than reusing {@code HandshakeInitPayload} for both directions: the two
 * marker values ({@code HANDSHAKE_INIT}/{@code HANDSHAKE_RESPONSE}) are already distinct in {@code
 * EnvelopeType}, and keeping them as distinct types here (rather than one payload plus a boolean
 * "is this a response" flag) is what lets {@code ApplicationMessageRouter}'s dispatch and {@code
 * SessionManager}'s handling stay a plain exhaustive {@code switch} on type, the same pattern
 * every other message kind in this project already uses.
 *
 * @param senderAddress          this node's own dialable multiaddr — see {@link
 *                               HandshakeInitPayload}'s own Javadoc for why this field exists.
 *                               Not actually used for a further reply (the handshake is complete
 *                               once a response arrives), but present for symmetry and so a
 *                               future capability re-negotiation has somewhere to send one.
 * @param supportedCapabilities see {@link HandshakeInitPayload}'s own Javadoc — identical
 *                               contract, just describing the responding side's capabilities.
 */
public record HandshakeResponsePayload(String senderAddress, Set<String> supportedCapabilities)
        implements HandshakeWireMessage {

    public HandshakeResponsePayload {
        if (senderAddress == null || senderAddress.isEmpty()) {
            throw new IllegalArgumentException("senderAddress must not be null or empty");
        }
        Objects.requireNonNull(supportedCapabilities, "supportedCapabilities");
        for (String capability : supportedCapabilities) {
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("supportedCapabilities must not contain null/blank entries");
            }
        }
        supportedCapabilities = Set.copyOf(supportedCapabilities);
    }
}
