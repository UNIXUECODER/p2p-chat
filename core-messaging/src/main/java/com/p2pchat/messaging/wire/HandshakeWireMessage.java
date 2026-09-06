package com.p2pchat.messaging.wire;

/**
 * pre-m6h-hardening-plan.md finding B-2: the two capability-negotiation message kinds that travel
 * under {@code EnvelopeType}'s reserved {@code HANDSHAKE_INIT}/{@code HANDSHAKE_RESPONSE} marker
 * values (0/1) — previously reserved-but-unimplemented, treated as routing errors by {@code
 * ApplicationMessageRouter} (see that class's own Javadoc, written when this genuinely was just a
 * "shouldn't happen yet" placeholder).
 *
 * <p>Its own sealed hierarchy, not folded into {@link ChatWireMessage} — same reasoning
 * {@code ChatWireMessage}'s own Javadoc already gives for why it isn't folded into {@code
 * FileTransferMessage}: this is a distinct concept (session capability negotiation, not a chat
 * message) that happens to share the same wire dispatch mechanism only because {@code
 * ApplicationMessageRouter} already multiplexes on a single marker byte across everything an
 * established session can carry.
 */
public sealed interface HandshakeWireMessage
        permits HandshakeInitPayload, HandshakeResponsePayload {
}
