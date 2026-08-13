package com.p2pchat.network;

import com.p2pchat.model.PeerId;

/** Callback invoked whenever an Envelope message arrives from a peer. */
public interface OnEnvelopeMessage {
    void onMessage(PeerId sender, byte[] data);
}
