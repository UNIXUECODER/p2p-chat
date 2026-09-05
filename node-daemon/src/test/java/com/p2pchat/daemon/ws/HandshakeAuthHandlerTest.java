package com.p2pchat.daemon.ws;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pre-m6h-hardening-plan.md finding C-1. Same technique as {@code DaemonWebSocketFrameHandlerTest}
 * — Netty's own {@code EmbeddedChannel}, trusting the library for protocol-level correctness and
 * verifying only what this project actually wrote — and the same honest verification-status
 * caveat that file already states: hand-traced against the actual Netty API (the real
 * {@code 4.2.10.Final} source was pulled and read for this, not assumed — see
 * {@link HandshakeAuthHandler}'s own Javadoc), but not executed here (no Netty jar reachable in
 * this environment). Needs a real {@code ./gradlew test} run to confirm.
 */
class HandshakeAuthHandlerTest {

    private static final String TOKEN = "correct-token-value";

    private static final class RecordingHandler extends ChannelInboundHandlerAdapter {
        final List<FullHttpRequest> received = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                received.add(request);
            }
        }
    }

    private static FullHttpRequest request(String uri, String origin) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri, Unpooled.EMPTY_BUFFER);
        if (origin != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        return request;
    }

    @Test
    void requestWithCorrectTokenAndNoOriginIsForwarded() {
        RecordingHandler recorder = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HandshakeAuthHandler(Set.of(), TOKEN), recorder);

        // No Origin header at all -- the normal case for a non-browser client (a CLI tool,
        // Electron's own main-process WebSocket client) -- must be allowed regardless of an
        // empty allowedOrigins set. See HandshakeAuthHandler's own constructor Javadoc.
        channel.writeInbound(request("/v1?token=" + TOKEN, null));

        assertThat(recorder.received).hasSize(1);
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void requestWithCorrectTokenAndAllowedOriginIsForwarded() {
        RecordingHandler recorder = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HandshakeAuthHandler(Set.of("app://p2p-chat"), TOKEN), recorder);

        channel.writeInbound(request("/v1?token=" + TOKEN, "app://p2p-chat"));

        assertThat(recorder.received).hasSize(1);
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void requestWithDisallowedOriginIsRejectedEvenWithCorrectToken() {
        // The actual threat this exists for: a malicious website's own Origin, which a real
        // browser attaches automatically and does NOT let page script override -- see this
        // class's own reference to the audit on why this is the only real defence available.
        RecordingHandler recorder = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HandshakeAuthHandler(Set.of(), TOKEN), recorder);

        channel.writeInbound(request("/v1?token=" + TOKEN, "https://evil.example.com"));

        assertThat(recorder.received).isEmpty();
        assertRejectedWith403(channel);
    }

    @Test
    void requestWithWrongTokenIsRejected() {
        RecordingHandler recorder = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HandshakeAuthHandler(Set.of(), TOKEN), recorder);

        channel.writeInbound(request("/v1?token=wrong-value", null));

        assertThat(recorder.received).isEmpty();
        assertRejectedWith403(channel);
    }

    @Test
    void requestWithNoTokenAtAllIsRejected() {
        RecordingHandler recorder = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HandshakeAuthHandler(Set.of(), TOKEN), recorder);

        channel.writeInbound(request("/v1", null));

        assertThat(recorder.received).isEmpty();
        assertRejectedWith403(channel);
    }

    @Test
    void nonHttpMessagesPassThroughUnaffected() {
        // Simulates a WebSocketFrame arriving after a successful upgrade -- this handler stays in
        // the pipeline for the connection's whole lifetime (it's never removed the way
        // WebSocketServerProtocolHandler's own internal handshake handler is), so it must be a
        // no-op for anything that isn't the initial handshake request.
        RecordingHandler recorder = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(new HandshakeAuthHandler(Set.of(), TOKEN), recorder);

        channel.writeInbound("not an http request");

        assertThat(channel.isOpen()).isTrue();
    }

    private static void assertRejectedWith403(EmbeddedChannel channel) {
        Object outbound = channel.readOutbound();
        assertThat(outbound).isInstanceOf(FullHttpResponse.class);
        assertThat(((FullHttpResponse) outbound).status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(channel.isOpen()).isFalse();
    }
}
