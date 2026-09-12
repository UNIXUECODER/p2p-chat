package com.p2pchat.network;

/**
 * The four kinds of frame that travel over the Relay protocol (see {@link RelayFrame}, {@link
 * RelayFrameCodec}). FORWARD and DELIVER existed since M3a (client-to-relay "please forward this"
 * / relay-to-client "here's a delivery"). PING and PONG are new for
 * pre-m6h-hardening-plan.md's Track A / A-1 (persistent relay session): a lightweight keepalive
 * exchanged directly between a client and the relay server it's connected to.
 *
 * <p>PING/PONG are answered at the protocol layer itself (see {@link RelayProtocol}) — whichever
 * side receives a PING echoes a PONG straight back, without ever reaching {@link
 * RelayEventHandler#onFrame}. That keeps the mechanism symmetric (works identically whether this
 * side is the relay or the client) and means {@code relay-server}'s own registry needs zero code
 * to support it: liveness-checking is {@link RelaySession}'s concern, not the registry's.
 */
public enum RelayFrameType {
    FORWARD,
    DELIVER,
    PING,
    PONG
}
