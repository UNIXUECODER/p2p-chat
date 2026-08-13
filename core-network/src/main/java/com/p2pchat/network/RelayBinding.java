package com.p2pchat.network;

import io.libp2p.core.multistream.StrictProtocolBinding;

public class RelayBinding extends StrictProtocolBinding<RelayController> {
    public static final String PROTOCOL_ID = "/p2p-chat/relay/0.1.0";

    public RelayBinding(RelayEventHandler eventHandler) {
        super(PROTOCOL_ID, new RelayProtocol(eventHandler));
    }
}
