package com.p2pchat.network;

import io.libp2p.core.multistream.StrictProtocolBinding;

public class DiscoveryBinding extends StrictProtocolBinding<DiscoveryController> {
    public static final String PROTOCOL_ID = "/p2p-chat/discovery/0.1.0";

    public DiscoveryBinding(DiscoveryRequestHandler requestHandler) {
        super(PROTOCOL_ID, new DiscoveryProtocol(requestHandler));
    }
}
