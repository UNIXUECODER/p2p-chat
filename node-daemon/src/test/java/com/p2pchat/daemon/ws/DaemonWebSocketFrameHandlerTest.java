package com.p2pchat.daemon.ws;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6d. Deliberately scoped to {@link DaemonWebSocketFrameHandler}'s own logic — session
 * registration, message forwarding, disconnect cleanup — via Netty's own {@code EmbeddedChannel}
 * test utility, not to re-proving {@code WebSocketServerProtocolHandler}'s handshake/framing
 * correctness, which is Netty's job and already covered by Netty's own test suite. This mirrors
 * the exact reasoning behind choosing Netty in the first place: trust the library for the part
 * with real interoperability stakes, verify only the part this project actually wrote.
 *
 * <p><b>Verification status, honestly:</b> this stub-compiles against hand-traced Netty API
 * shapes confirmed by reading the actual tagged {@code netty-4.1}/{@code netty-4.2} source
 * before writing anything — but cannot be executed in this sandbox (no Netty jar reachable here,
 * same limitation as the crypto-dependent parts of M6e-1). This is the one piece of M6 so far
 * with no real execution behind it at all, not even the "real SQLite, fake crypto" middle ground
 * M6e-1 had. Running this for real is what actually closes that gap.
 */
class DaemonWebSocketFrameHandlerTest {

    private static final class RecordingTextHandler implements WebSocketTextHandler {
        final List<WebSocketSession> connected = new ArrayList<>();
        final List<WebSocketSession> disconnected = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public void onMessage(WebSocketSession session, String text) {
            messages.add(text);
        }

        @Override
        public void onConnect(WebSocketSession session) {
            connected.add(session);
        }

        @Override
        public void onDisconnect(WebSocketSession session) {
            disconnected.add(session);
        }
    }

    @Test
    void handshakeCompleteRegistersASessionAndCallsOnConnect() {
        RecordingTextHandler recorder = new RecordingTextHandler();
        Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        EmbeddedChannel channel = new EmbeddedChannel(new DaemonWebSocketFrameHandler(recorder, sessions));

        channel.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/v1", null, null));

        assertThat(recorder.connected).hasSize(1);
        assertThat(sessions).hasSize(1);
    }

    @Test
    void textFrameAfterHandshakeReachesOnMessageWithTheDecodedText() {
        RecordingTextHandler recorder = new RecordingTextHandler();
        Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        EmbeddedChannel channel = new EmbeddedChannel(new DaemonWebSocketFrameHandler(recorder, sessions));
        channel.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/v1", null, null));

        channel.writeInbound(new TextWebSocketFrame("{\"jsonrpc\":\"2.0\"}"));

        assertThat(recorder.messages).containsExactly("{\"jsonrpc\":\"2.0\"}");
    }

    @Test
    void aMessageBeforeHandshakeCompletesIsNotDeliveredAnywhereRatherThanNullPointerException() {
        // No HandshakeComplete fired first -- the session map has no entry yet for this
        // channel's id. This shouldn't be reachable in practice (WebSocketServerProtocolHandler
        // only forwards frames after a real handshake), but a defensive null-check here means a
        // pipeline-ordering mistake fails safely instead of throwing out of a Netty I/O thread.
        RecordingTextHandler recorder = new RecordingTextHandler();
        Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        EmbeddedChannel channel = new EmbeddedChannel(new DaemonWebSocketFrameHandler(recorder, sessions));

        channel.writeInbound(new TextWebSocketFrame("too early"));

        assertThat(recorder.messages).isEmpty();
    }

    @Test
    void channelCloseRemovesTheSessionAndCallsOnDisconnect() {
        RecordingTextHandler recorder = new RecordingTextHandler();
        Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        EmbeddedChannel channel = new EmbeddedChannel(new DaemonWebSocketFrameHandler(recorder, sessions));
        channel.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/v1", null, null));
        assertThat(sessions).hasSize(1);

        channel.close();

        assertThat(recorder.disconnected).hasSize(1);
        assertThat(sessions).isEmpty();
    }
}
