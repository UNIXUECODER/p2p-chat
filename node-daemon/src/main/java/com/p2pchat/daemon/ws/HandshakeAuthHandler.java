package com.p2pchat.daemon.ws;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/**
 * pre-m6h-hardening-plan.md finding C-1, changes 2 and 3 of its three required changes (loopback
 * binding is change 1, done in {@link DaemonWebSocketServer#start}). Sits between {@code
 * HttpObjectAggregator} and {@code WebSocketServerProtocolHandler} in the pipeline — after
 * aggregation so this always sees a complete {@link FullHttpRequest}, before the protocol
 * handler so a rejected handshake never reaches it at all.
 *
 * <p><b>Why a {@code ChannelInboundHandlerAdapter}, not a {@code SimpleChannelInboundHandler}:</b>
 * the latter auto-releases the inbound message after {@code channelRead0} returns, which would
 * break {@code WebSocketServerProtocolHandler} downstream on the success path — this handler
 * forwards the request unmodified via {@code ctx.fireChannelRead(request)}, and Netty's
 * reference-counting contract means whoever forwards a message must not also release it.
 *
 * <p><b>Token via query string, confirmed against Netty's actual matching logic, not assumed:</b>
 * pulled the real {@code WebSocketServerProtocolHandshakeHandler} source (both the {@code 4.1}
 * and {@code 4.2} branches — {@code 4.2.10.Final} is what this project actually bundles,
 * confirmed against the resolved dependency tree) before writing this. Its path match is an exact
 * {@code uri.equals(websocketPath)} by default, meaning a URI of {@code "/v1?token=..."} would
 * silently fail to match {@code "/v1"} and the WebSocket upgrade would never happen at all — not
 * an error, just a hung connection. Netty has a {@code checkStartsWith} flag specifically for
 * this (confirmed by reading {@code isWebSocketPath}/{@code checkNextUri}'s own logic: with it
 * enabled, a {@code ?} immediately following the configured path is treated as a valid boundary,
 * same as a following {@code /}) — {@link DaemonWebSocketServer#start} enables it for exactly
 * this reason. Worth recording here since it's exactly the kind of interaction between two
 * pieces of code that looks obviously fine in isolation and silently breaks everything together.
 */
@ChannelHandler.Sharable
final class HandshakeAuthHandler extends ChannelInboundHandlerAdapter {

    private final Set<String> allowedOrigins;
    private final String requiredToken;

    /**
     * @param allowedOrigins Exact {@code Origin} header values to accept. A handshake with no
     *                        {@code Origin} header at all is always allowed regardless of this
     *                        set — that's the normal case for non-browser clients (a CLI tool,
     *                        Electron's own main-process WebSocket client) which don't send one.
     *                        A handshake WITH an {@code Origin} header not in this set is
     *                        rejected — that's the actual browser-facing threat this exists for
     *                        (see this class's own top-level Javadoc reference to the audit).
     *                        An empty set — the sensible default until M7's Electron app exists
     *                        and its real origin value is known — means no {@code Origin} header
     *                        value is acceptable, i.e. any handshake presenting one is rejected.
     * @param requiredToken   The exact token every handshake must present as a {@code token}
     *                        query parameter. Compared with {@link MessageDigest#isEqual}, not
     *                        {@link String#equals}, specifically to avoid a timing side-channel
     *                        on the comparison.
     */
    HandshakeAuthHandler(Set<String> allowedOrigins, String requiredToken) {
        this.allowedOrigins = allowedOrigins;
        this.requiredToken = requiredToken;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            // Everything after a successful upgrade (WebSocketFrames) and anything
            // HttpObjectAggregator hasn't finished assembling yet -- neither is this handler's
            // concern, so it passes through untouched rather than only being wired in
            // conditionally.
            ctx.fireChannelRead(msg);
            return;
        }

        String rejectionReason = rejectionReason(request);
        if (rejectionReason == null) {
            ctx.fireChannelRead(request); // NOT released here -- ownership passes downstream, see class Javadoc
            return;
        }

        System.out.println("[ws] rejecting handshake from " + ctx.channel().remoteAddress() + ": " + rejectionReason);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        request.release(); // this handler is consuming the request (not forwarding it), so it must release it
    }

    private String rejectionReason(FullHttpRequest request) {
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            return "disallowed Origin header: " + origin;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> tokenValues = decoder.parameters().get("token");
        String suppliedToken = (tokenValues == null || tokenValues.isEmpty()) ? "" : tokenValues.get(0);
        if (!constantTimeEquals(suppliedToken, requiredToken)) {
            return "missing or incorrect token";
        }

        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
