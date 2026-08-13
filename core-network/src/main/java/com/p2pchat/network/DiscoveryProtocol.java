package com.p2pchat.network;

import com.p2pchat.model.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.protocol.ProtocolHandler;
import io.libp2p.protocol.ProtocolMessageHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M3c: publish/lookup discovery, structured on jvm-libp2p's own Ping
 * protocol (io.libp2p.protocol.Ping — read in full before writing this,
 * not paraphrased from memory) rather than Envelope/Relay's symmetric
 * shape. Ping is the proven pattern for "one side issues correlated
 * requests and waits for responses, the other side answers them," which is
 * exactly Discovery's shape — neither Envelope's nor Relay's.
 *
 * Simplified from Ping in one deliberate way: Ping supports many concurrent
 * in-flight requests per connection (it needs a correlation map, since it's
 * meant to be a long-lived, reusable channel). Discovery dials fresh per
 * operation instead, matching sendEnvelope/pingPeer's existing one-shot-dial
 * convention — so only ONE pending request per connection is possible by
 * construction, making a single field correct rather than a shortcut, as
 * long as a controller is never reused for a second concurrent lookup
 * (guarded against explicitly below, rather than silently corrupting state).
 */
public class DiscoveryProtocol extends ProtocolHandler<DiscoveryController> {

    private static final long LOOKUP_TIMEOUT_SECONDS = 5;

    private final DiscoveryRequestHandler requestHandler;

    public DiscoveryProtocol(DiscoveryRequestHandler requestHandler) {
        super(Long.MAX_VALUE, Long.MAX_VALUE);
        this.requestHandler = requestHandler;
    }

    @Override
    protected CompletableFuture<DiscoveryController> onStartInitiator(Stream stream) {
        Initiator initiator = new Initiator();
        stream.pushHandler(initiator);
        return initiator.activeFuture;
    }

    @Override
    protected CompletableFuture<DiscoveryController> onStartResponder(Stream stream) {
        Responder responder = new Responder(requestHandler);
        stream.pushHandler(responder);
        // Unlike Initiator, the responder doesn't need to wait for anything before
        // being usable — mirrors PingResponder's identical immediate-completion choice.
        return CompletableFuture.completedFuture(responder);
    }

    private static class Initiator implements ProtocolMessageHandler<ByteBuf>, DiscoveryController {
        final CompletableFuture<DiscoveryController> activeFuture = new CompletableFuture<>();
        private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        private Stream stream;
        // AtomicReference rather than a plain volatile field: onMessage (a Netty I/O
        // thread) and the timeout scheduler (a separate thread) both need to
        // atomically "claim" the pending future — a plain field would let both
        // threads pass a null-check before either clears it, a real TOCTOU race,
        // even though CompletableFuture itself tolerates the resulting double-complete
        // attempt gracefully. getAndSet(null) makes the claim atomic instead.
        private final AtomicReference<CompletableFuture<DiscoveryLookupResult>> pendingLookup = new AtomicReference<>();

        @Override
        public void onActivated(Stream stream) {
            this.stream = stream;
            activeFuture.complete(this);
        }

        @Override
        public void onMessage(Stream stream, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            DiscoveryFrame frame = DiscoveryFrameCodec.decode(data);

            if (frame.type() != DiscoveryMessageType.LOOKUP_RESPONSE_FOUND
                    && frame.type() != DiscoveryMessageType.LOOKUP_RESPONSE_NOT_FOUND) {
                return; // not a response we're tracking — ignore rather than fail loudly
            }

            CompletableFuture<DiscoveryLookupResult> pending = pendingLookup.getAndSet(null);
            if (pending == null) {
                return; // already claimed by the timeout, or genuinely unsolicited
            }
            if (frame.type() == DiscoveryMessageType.LOOKUP_RESPONSE_FOUND) {
                pending.complete(new DiscoveryLookupResult(true, frame.payload()));
            } else {
                pending.complete(new DiscoveryLookupResult(false, new byte[0]));
            }
        }

        @Override
        public void onClosed(Stream stream) {
            CompletableFuture<DiscoveryLookupResult> pending = pendingLookup.getAndSet(null);
            if (pending != null) {
                pending.completeExceptionally(new IllegalStateException("Discovery connection closed while a lookup was pending"));
            }
            timeoutScheduler.shutdownNow();
        }

        @Override
        public void onReadClosed(Stream stream) {
            // no-op
        }

        @Override
        public void onException(Throwable cause) {
            // no-op
        }

        @Override
        public void publish(byte[] payload) {
            stream.writeAndFlush(Unpooled.wrappedBuffer(
                    DiscoveryFrameCodec.encode(new DiscoveryFrame(DiscoveryMessageType.PUBLISH, "", payload))));
        }

        @Override
        public CompletableFuture<DiscoveryLookupResult> lookup(String targetPeerId) {
            CompletableFuture<DiscoveryLookupResult> future = new CompletableFuture<>();
            if (!pendingLookup.compareAndSet(null, future)) {
                future.completeExceptionally(new IllegalStateException(
                        "A lookup is already pending on this connection — dial a fresh one for a concurrent lookup"));
                return future;
            }

            timeoutScheduler.schedule(() -> {
                if (pendingLookup.compareAndSet(future, null)) {
                    future.completeExceptionally(new TimeoutException("Discovery lookup timed out"));
                }
            }, LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            stream.writeAndFlush(Unpooled.wrappedBuffer(
                    DiscoveryFrameCodec.encode(new DiscoveryFrame(DiscoveryMessageType.LOOKUP, targetPeerId, new byte[0]))));
            return future;
        }
    }

    private static class Responder implements ProtocolMessageHandler<ByteBuf>, DiscoveryController {
        private final DiscoveryRequestHandler requestHandler;
        private Stream stream;

        Responder(DiscoveryRequestHandler requestHandler) {
            this.requestHandler = requestHandler;
        }

        @Override
        public void onActivated(Stream stream) {
            this.stream = stream;
        }

        @Override
        public void onMessage(Stream stream, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            DiscoveryFrame frame = DiscoveryFrameCodec.decode(data);

            if (frame.type() == DiscoveryMessageType.PUBLISH) {
                // M3d: convert at the boundary, same as EnvelopeProtocol/RelayProtocol.
                requestHandler.onPublish(PeerId.of(stream.remotePeerId().toString()), frame.payload());
            } else if (frame.type() == DiscoveryMessageType.LOOKUP) {
                byte[] result = requestHandler.onLookup(frame.peerId());
                DiscoveryFrame response = (result != null)
                        ? new DiscoveryFrame(DiscoveryMessageType.LOOKUP_RESPONSE_FOUND, frame.peerId(), result)
                        : new DiscoveryFrame(DiscoveryMessageType.LOOKUP_RESPONSE_NOT_FOUND, frame.peerId(), new byte[0]);
                this.stream.writeAndFlush(Unpooled.wrappedBuffer(DiscoveryFrameCodec.encode(response)));
            }
            // A LOOKUP_RESPONSE_* arriving here would be a protocol violation — ignore defensively.
        }

        @Override
        public void onClosed(Stream stream) {
            // no-op
        }

        @Override
        public void onReadClosed(Stream stream) {
            // no-op
        }

        @Override
        public void onException(Throwable cause) {
            // no-op
        }

        @Override
        public void publish(byte[] payload) {
            throw new UnsupportedOperationException("This is the discovery responder side — nothing to publish from here");
        }

        @Override
        public CompletableFuture<DiscoveryLookupResult> lookup(String targetPeerId) {
            throw new UnsupportedOperationException("This is the discovery responder side — nothing to look up from here");
        }
    }
}
