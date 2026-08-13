package com.p2pchat.network;

import io.libp2p.core.multistream.StrictProtocolBinding;

/**
 * Binds EnvelopeProtocol to a protocol ID other peers negotiate against.
 * Mirrors jvm-libp2p's own PingBinding (announce = "/ipfs/ping/1.0.0") exactly,
 * just with our own protocol ID and handler.
 */
public class EnvelopeBinding extends StrictProtocolBinding<EnvelopeController> {
    public static final String PROTOCOL_ID = "/p2p-chat/envelope/0.1.0";

    public EnvelopeBinding(OnEnvelopeMessage onMessage) {
        super(PROTOCOL_ID, new EnvelopeProtocol(onMessage));
    }
}
