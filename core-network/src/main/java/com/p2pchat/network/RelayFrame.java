package com.p2pchat.network;

/**
 * A single relay message. If isForwardRequest, `peerId` is the TARGET the
 * sender wants this forwarded to (client → relay). If not, `peerId` is the
 * ORIGINAL SENDER this was forwarded from (relay → client, i.e. a delivery).
 * Same shape serves both directions — only the marker byte differs on the wire.
 */
public record RelayFrame(boolean isForwardRequest, String peerId, byte[] payload) {
}
