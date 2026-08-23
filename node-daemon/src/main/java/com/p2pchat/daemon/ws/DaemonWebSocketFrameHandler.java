package com.p2pchat.daemon.ws;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Map;

/**
 * M6d: sits after {@code WebSocketServerProtocolHandler} in the pipeline. Deliberately thin —
 * ping/pong/close frames never reach here at all, handled upstream by {@code
 * WebSocketServerProtocolHandler} itself (confirmed via its own Javadoc before writing this,
 * not assumed); this class only ever sees {@link TextWebSocketFrame}s and the handshake-complete
 * / channel-inactive lifecycle events.
 *
 * <p>Marked {@link ChannelHandler.Sharable} and constructed once by {@link
 * DaemonWebSocketServer}, not per-connection — it holds no per-connection state of its own, only
 * references to the shared session registry and {@link WebSocketTextHandler}, so one instance
 * safely serves every connected channel.
 */
@ChannelHandler.Sharable
final class DaemonWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final WebSocketTextHandler textHandler;
    private final Map<String, WebSocketSession> sessions;

    DaemonWebSocketFrameHandler(WebSocketTextHandler textHandler, Map<String, WebSocketSession> sessions) {
        this.textHandler = textHandler;
        this.sessions = sessions;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // Only TextWebSocketFrame ever reaches here in practice -- ping/pong/close are consumed
        // upstream, and this project never sends binary frames (JSON-RPC is text-only). A
        // BinaryWebSocketFrame arriving would mean a client doing something this protocol
        // doesn't define; closing rather than silently ignoring it is the more honest response.
        if (frame instanceof TextWebSocketFrame textFrame) {
            WebSocketSession session = sessions.get(ctx.channel().id().asShortText());
            if (session != null) {
                textHandler.onMessage(session, textFrame.text());
            }
        } else {
            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            WebSocketSession session = new WebSocketSession(ctx.channel());
            sessions.put(session.id(), session);
            textHandler.onConnect(session);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        WebSocketSession session = sessions.remove(ctx.channel().id().asShortText());
        if (session != null) {
            textHandler.onDisconnect(session);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
