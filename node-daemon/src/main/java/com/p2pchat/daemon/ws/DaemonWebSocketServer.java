package com.p2pchat.daemon.ws;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M6d: `ws://127.0.0.1:&lt;port&gt;/v1` — the transport `architecture-spec.md §7` describes the
 * Electron app connecting to. Nothing JSON-RPC-specific here; that's M6g's job, plugged in via
 * {@link WebSocketTextHandler}. This class only gets raw text frames to and from connected
 * clients.
 *
 * <p><b>Netty, decided deliberately, not a default.</b> {@code jvm-libp2p} already pulls Netty
 * transitively — confirmed by resolving the real dependency tree, not assumed — so this adds no
 * new dependency. More importantly: hand-rolling RFC 6455 framing (client-frame masking,
 * fragmentation, the close handshake) is real protocol surface with sharp edges, and a
 * hand-rolled implementation's own tests can only prove it agrees with itself, not that a real
 * browser's native {@code WebSocket} will accept it. {@code WebSocketServerProtocolHandler} is
 * the actual, already-interoperable implementation browsers talk to correctly — using it isn't
 * a compromise on this project's "hand-roll the wire format" convention, since unlike every
 * other wire format here (chat, file transfer, relay, discovery), this one only exists to talk
 * to code this project doesn't control.
 *
 * <p><b>Pipeline order</b> — confirmed against Netty's own {@code netty-4.1}/{@code netty-4.2}
 * branch example source, not written from memory: {@code HttpServerCodec} → {@code
 * HttpObjectAggregator} → {@code WebSocketServerProtocolHandler} → {@link
 * DaemonWebSocketFrameHandler}. Ping/pong/close frames never reach this project's own code —
 * handled entirely by {@code WebSocketServerProtocolHandler} itself, confirmed via its own
 * Javadoc, which is a large part of the actual case for using it at all.
 *
 * <p><b>{@code allowExtensions = false}</b>, deliberately, not the {@code true} Netty's own
 * example uses — that example also wires in {@code WebSocketServerCompressionHandler}; {@code
 * allowExtensions} is what lets a handshake negotiate {@code permessage-deflate} with a client,
 * and advertising that without a compression handler actually present to implement it would be
 * a real, if subtle, protocol mismatch. permessage-deflate was already out of scope when M6 was
 * first planned — this is that decision actually being honored, not revisited.
 *
 * <p><b>A dedicated {@link EventLoopGroup} pair</b>, entirely separate from whatever {@code
 * jvm-libp2p} manages internally for {@code PeerNetworkService} — this class never touches that,
 * and {@code PeerNetworkService} exposes no hook to share one even if it seemed appealing to.
 * Two independent Netty-based subsystems in one process, not one shared implicitly.
 *
 * <p><b>pre-m6h-hardening-plan.md finding C-1</b> — three changes, all here: binds to loopback
 * only (not every interface, which is what a bare {@code bind(port)} does), requires an {@link
 * HandshakeAuthHandler} check (Origin allowlist + token) on every connection attempt before
 * {@code WebSocketServerProtocolHandler} ever sees it, and generates the required token itself
 * in the convenience constructor via {@link RpcAuthToken} — see that class's own Javadoc for why
 * it's regenerated fresh per daemon start rather than persisted long-term. The token file's path
 * is a constructor parameter, not hardcoded to the audit's own suggested {@code ~/.p2p-chat} —
 * this project's actual established convention (see every existing {@code *Main} class in this
 * module) is a {@code p2pchat.dataDir}-relative directory, decided by whatever constructs this
 * class, not assumed here. M6h's {@code DaemonMain} is what will make that real decision.
 */
public final class DaemonWebSocketServer implements AutoCloseable {

    private static final int MAX_HTTP_CONTENT_LENGTH = 65536;
    private static final int MAX_FRAME_SIZE = 65536;

    private final String path;
    private final WebSocketTextHandler textHandler;
    private final Set<String> allowedOrigins;
    private final String requiredToken;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * Generates a fresh token itself (see {@link RpcAuthToken}), persisted to {@code tokenFile},
     * and starts with an empty Origin allowlist — meaning no {@code Origin} header value is
     * accepted at all, the correct default until M7's Electron app exists and its real origin is
     * known (see {@link HandshakeAuthHandler}'s own Javadoc on what an empty set means).
     */
    public DaemonWebSocketServer(String path, WebSocketTextHandler textHandler, Path tokenFile) throws IOException {
        this(path, textHandler, Set.of(), RpcAuthToken.generateAndPersist(tokenFile));
    }

    public DaemonWebSocketServer(String path, WebSocketTextHandler textHandler, Set<String> allowedOrigins,
                                  String requiredToken) {
        this.path = path;
        this.textHandler = textHandler;
        this.allowedOrigins = allowedOrigins;
        this.requiredToken = requiredToken;
    }

    /**
     * Binds and starts listening on {@code port}. Blocks until bound (or throws), matching this
     * project's existing {@code PeerNetworkService.start(...)} convention rather than returning
     * a future a caller would need to await regardless.
     */
    public void start(int port) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        DaemonWebSocketFrameHandler frameHandler = new DaemonWebSocketFrameHandler(textHandler, sessions);
        HandshakeAuthHandler authHandler = new HandshakeAuthHandler(allowedOrigins, requiredToken);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                        pipeline.addLast(authHandler);
                        // checkStartsWith=true (the 5th boolean here, after allowExtensions=false
                        // and maxFrameSize) -- required so a URI of "/v1?token=..." still matches
                        // the configured "/v1" path. See HandshakeAuthHandler's own Javadoc for
                        // why this isn't optional once a token lives in the query string.
                        pipeline.addLast(new WebSocketServerProtocolHandler(path, null, false, MAX_FRAME_SIZE, false, true));
                        pipeline.addLast(frameHandler);
                    }
                });

        // Bound to loopback explicitly -- a bare bind(port) binds every interface (0.0.0.0),
        // meaning any device on the same LAN (or, if the host's own firewall doesn't block it,
        // in principle anything routing to it) could reach the JSON-RPC API. See this class's
        // own Javadoc reference to the hardening audit.
        serverChannel = bootstrap.bind("127.0.0.1", port).sync().channel();
    }

    /** Pushes {@code text} to every currently connected client — what M6g's {@code event.*} push events will use. */
    public void broadcast(String text) {
        for (WebSocketSession session : sessions.values()) {
            session.send(text);
        }
    }

    public Collection<WebSocketSession> sessions() {
        return sessions.values();
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }
}
