package com.p2pchat.network;

import com.p2pchat.model.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;
import io.libp2p.protocol.ProtocolMessageHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.concurrent.CompletableFuture;

/**
 * M2b: a minimal custom libp2p protocol that carries arbitrary bytes between
 * two peers, registered alongside the built-in `ping` protocol proven in M1.
 * This is what will carry PQXDH handshakes and Double-Ratchet-encrypted
 * messages once wired to core-crypto in M2c — for M2b it only proves the
 * pipe itself works for OUR data, not libp2p's.
 *
 * Structure mirrors jvm-libp2p's own Ping protocol (io.libp2p.protocol.Ping)
 * and the official "chatter" example protocol exactly, both verified against
 * the real v1.3.4 source before writing this — not written from memory.
 *
 * All five ProtocolMessageHandler methods are implemented explicitly, even
 * the no-op ones, rather than relying on Kotlin's interface default bodies —
 * whether those come through as real Java default methods depends on
 * compiler flags this project doesn't control, so this is the safer choice.
 */
public class EnvelopeProtocol extends ProtocolHandler<EnvelopeController> {

    private final OnEnvelopeMessage onMessage;

    public EnvelopeProtocol(OnEnvelopeMessage onMessage) {
        // No traffic limit on either side — message size is our own concern at the application layer.
        super(Long.MAX_VALUE, Long.MAX_VALUE);
        this.onMessage = onMessage;
    }

    @Override
    protected CompletableFuture<EnvelopeController> onStartInitiator(Stream stream) {
        return onStart(stream);
    }

    @Override
    protected CompletableFuture<EnvelopeController> onStartResponder(Stream stream) {
        return onStart(stream);
    }

    private CompletableFuture<EnvelopeController> onStart(Stream stream) {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Handler handler = new Handler(onMessage, ready);
        stream.pushHandler(handler);
        return ready.thenApply(v -> handler);
    }

    private static class Handler implements ProtocolMessageHandler<ByteBuf>, EnvelopeController {
        private final OnEnvelopeMessage onMessage;
        private final CompletableFuture<Void> ready;
        private Stream stream;

        Handler(OnEnvelopeMessage onMessage, CompletableFuture<Void> ready) {
            this.onMessage = onMessage;
            this.ready = ready;
        }

        @Override
        public void onActivated(Stream stream) {
            this.stream = stream;
            ready.complete(null);
        }

        @Override
        public void onMessage(Stream stream, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            // M3d: stream.remotePeerId() returns io.libp2p.core.PeerId — convert to our
            // canonical model type right here, at the boundary, so nothing on the other
            // side of OnEnvelopeMessage needs to know libp2p's type exists.
            onMessage.onMessage(PeerId.of(stream.remotePeerId().toString()), data);
        }

        @Override
        public void onClosed(Stream stream) {
            // no-op for M2b
        }

        @Override
        public void onReadClosed(Stream stream) {
            // no-op for M2b
        }

        @Override
        public void onException(Throwable cause) {
            // no-op for M2b — real error handling comes when this is wired into the daemon properly (M2c)
        }

        @Override
        public void send(byte[] data) {
            stream.writeAndFlush(Unpooled.wrappedBuffer(data));
        }
    }
}
