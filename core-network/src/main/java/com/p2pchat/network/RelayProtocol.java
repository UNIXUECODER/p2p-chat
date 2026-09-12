package com.p2pchat.network;

import com.p2pchat.model.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;
import io.libp2p.protocol.ProtocolMessageHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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

        // A-1: onClosed and onReadClosed can both fire for the same stream teardown (e.g. a
        // half-close followed shortly by a full close) — this flag is what makes onDisconnected's
        // own contract ("fires once") actually true, rather than relying on the caller to
        // de-duplicate.
        private final AtomicBoolean disconnectedFired = new AtomicBoolean(false);

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
            // A-1: PING is answered right here, transparently, on whichever side receives it —
            // this same Handler class runs as both the relay's and the client's end of the
            // stream (onStartInitiator/onStartResponder both call onStart), so no relay-server-
            // specific code is needed for the keepalive to work. Never reaches
            // RelayEventHandler.onFrame — see RelayFrameType's own Javadoc for why.
            if (frame.type() == RelayFrameType.PING) {
                send(new RelayFrame(RelayFrameType.PONG, "", frame.payload()));
                return;
            }
            eventHandler.onFrame(PeerId.of(stream.remotePeerId().toString()), frame);
        }

        @Override
        public void onClosed(Stream stream) {
            fireDisconnected();
        }

        @Override
        public void onReadClosed(Stream stream) {
            fireDisconnected();
        }

        private void fireDisconnected() {
            if (disconnectedFired.compareAndSet(false, true) && stream != null) {
                eventHandler.onDisconnected(PeerId.of(stream.remotePeerId().toString()), this);
            }
        }

        @Override
        public void onException(Throwable cause) {
            cause.printStackTrace();
        }

        @Override
        public void send(RelayFrame frame) {
            stream.writeAndFlush(Unpooled.wrappedBuffer(RelayFrameCodec.encode(frame)));
        }

        @Override
        public void close() {
            // NOT hardware-verified: Stream.close() is assumed here to return a
            // CompletableFuture<Unit> (the P2PChannel shape used elsewhere in jvm-libp2p, e.g.
            // Host.stop() in Libp2pNetworkService), same as every other call into a jvm-libp2p
            // type in this class already is — but this exact method wasn't previously called
            // anywhere in this codebase, so unlike writeAndFlush/remotePeerId above, no prior
            // milestone's hardware run has exercised this line specifically. Flagged per this
            // project's own verification vocabulary, not silently assumed correct: if
            // `./gradlew :core-network:compileJava` disagrees with this signature, this is the
            // line to fix.
            if (stream != null) {
                stream.close();
            }
        }
    }
}
