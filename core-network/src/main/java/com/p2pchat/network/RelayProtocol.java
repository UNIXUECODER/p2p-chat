package com.p2pchat.network;

import com.p2pchat.model.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;
import io.libp2p.protocol.ProtocolMessageHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.concurrent.CompletableFuture;

/**
 * M3a: a custom libp2p protocol for relaying arbitrary bytes between two
 * peers that can't reach each other directly, via a relay server both can
 * reach — since jvm-libp2p has no hole-punching, this is the actual
 * connectivity fallback, not a stopgap (see architecture-spec.md §10).
 *
 * Structurally the same ProtocolHandler/StrictProtocolBinding pattern as
 * EnvelopeProtocol (M2b), verified the same way against real jvm-libp2p
 * source. One real difference: Envelope connections were always short-lived
 * (dial, send, disconnect). Relay connections are deliberately long-lived on
 * the registering side, which is why RelayEventHandler (unlike
 * OnEnvelopeMessage) has an onConnected callback — it's the hook a caller
 * uses to capture the controller for reuse, rather than it only ever being
 * handed back once from a dial() call.
 */
public class RelayProtocol extends ProtocolHandler<RelayController> {

    private final RelayEventHandler eventHandler;

    public RelayProtocol(RelayEventHandler eventHandler) {
        super(Long.MAX_VALUE, Long.MAX_VALUE);
        this.eventHandler = eventHandler;
    }

    @Override
    protected CompletableFuture<RelayController> onStartInitiator(Stream stream) {
        return onStart(stream);
    }

    @Override
    protected CompletableFuture<RelayController> onStartResponder(Stream stream) {
        return onStart(stream);
    }

    private CompletableFuture<RelayController> onStart(Stream stream) {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Handler handler = new Handler(eventHandler, ready);
        stream.pushHandler(handler);
        return ready.thenApply(v -> handler);
    }

    private static class Handler implements ProtocolMessageHandler<ByteBuf>, RelayController {
        private final RelayEventHandler eventHandler;
        private final CompletableFuture<Void> ready;
        private Stream stream;

        Handler(RelayEventHandler eventHandler, CompletableFuture<Void> ready) {
            this.eventHandler = eventHandler;
            this.ready = ready;
        }

        @Override
        public void onActivated(Stream stream) {
            this.stream = stream;
            // M3d: convert at the boundary, same as EnvelopeProtocol — see the comment there.
            eventHandler.onConnected(PeerId.of(stream.remotePeerId().toString()), this);
            ready.complete(null);
        }

        @Override
        public void onMessage(Stream stream, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            RelayFrame frame = RelayFrameCodec.decode(data);
            eventHandler.onFrame(PeerId.of(stream.remotePeerId().toString()), frame);
        }

        @Override
        public void onClosed(Stream stream) {
            // no-op for M3a — a real implementation would deregister here; that's an M3b concern
        }

        @Override
        public void onReadClosed(Stream stream) {
            // no-op for M3a
        }

        @Override
        public void onException(Throwable cause) {
            cause.printStackTrace();
        }

        @Override
        public void send(RelayFrame frame) {
            stream.writeAndFlush(Unpooled.wrappedBuffer(RelayFrameCodec.encode(frame)));
        }
    }
}
