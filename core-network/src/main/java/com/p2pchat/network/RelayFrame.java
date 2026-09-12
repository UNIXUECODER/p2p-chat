package com.p2pchat.network;

/**
 * A single relay message. Four kinds now (see {@link RelayFrameType}):
 * <ul>
 *   <li>FORWARD: client → relay, {@code peerId} is the TARGET to forward this to.</li>
 *   <li>DELIVER: relay → client, {@code peerId} is the ORIGINAL SENDER this was forwarded from.</li>
 *   <li>PING / PONG: pre-m6h-hardening-plan.md's A-1 keepalive between a client and its relay —
 *   {@code peerId} is unused (always {@code ""}), {@code payload} carries an opaque nonce the
 *   receiving side echoes back unchanged. See {@link RelaySession}.</li>
 * </ul>
 * Same shape serves all four — only the marker byte differs on the wire.
 *
 * <p><b>A-1 note:</b> the original {@code boolean isForwardRequest} constructor and accessor
 * (M3a) are kept, not replaced, specifically so M3a/M6b's own already-executed tests and demo
 * Mains don't need to change at all for PING/PONG to exist — see {@link RelayFrameCodec}'s own
 * Javadoc for the equivalent reasoning on the wire-format side.
 */
public record RelayFrame(RelayFrameType type, String peerId, byte[] payload) {

    public RelayFrame(boolean isForwardRequest, String peerId, byte[] payload) {
        this(isForwardRequest ? RelayFrameType.FORWARD : RelayFrameType.DELIVER, peerId, payload);
    }

    public boolean isForwardRequest() {
        return type == RelayFrameType.FORWARD;
    }
}
