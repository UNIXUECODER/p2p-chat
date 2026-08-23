package com.p2pchat.daemon.ws;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * M6d: one connected WebSocket client. A thin wrapper around a Netty {@link Channel} — the
 * point is giving {@link WebSocketTextHandler} implementations (M6g, not yet built) something to
 * hold onto and call {@link #send} on without needing to import Netty types themselves, keeping
 * this milestone's Netty dependency contained to the {@code ws} package.
 */
public final class WebSocketSession {

    private final Channel channel;

    WebSocketSession(Channel channel) {
        this.channel = channel;
    }

    /** A stable identifier for this connection — Netty's own channel id, already unique. */
    public String id() {
        return channel.id().asShortText();
    }

    /**
     * Sends one WebSocket text frame to this client. Fire-and-forget from the caller's
     * perspective — matches this project's established "a send either works or the caller finds
     * out some other way" pattern ({@code PeerNetworkService.sendEnvelope}, {@code
     * ConnectionStrategy.send}) rather than returning a future every text send would need to be
     * awaited for.
     */
    public void send(String text) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(text));
        }
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public void close() {
        channel.close();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WebSocketSession other && channel.id().equals(other.channel.id());
    }

    @Override
    public int hashCode() {
        return channel.id().hashCode();
    }
}
