package com.p2pchat.network;

import com.p2pchat.model.PeerId;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A controllable {@link PeerNetworkService} double for {@code RelaySessionTest} — no jvm-libp2p
 * involved, the same "core-network's own classes never depend on jvm-libp2p types in their
 * signatures" property {@code OutboundMessageServiceTest}'s own Javadoc already established for
 * {@code ConnectionStrategy}, extended here to {@link RelaySession}. What makes real, executed
 * (not hand-traced) coverage of A-1's reconnect/keepalive state machine possible in a sandbox that
 * can't resolve jvm-libp2p at all.
 */
final class FakeRelayNetwork implements PeerNetworkService {

    private static final PeerId RELAY_PEER_ID = PeerId.of("relay-peer-id");

    private final AtomicInteger connectAttempts = new AtomicInteger(0);
    private volatile int remainingFailures = 0;
    private volatile boolean dropAllPings = false;
    private volatile FakeRelayController current;
    private volatile RelayEventHandler currentHandler;

    /** The next {@code count} calls to {@link #connectToRelay} throw; the one after that succeeds. */
    void failNextConnectAttempts(int count) {
        remainingFailures = count;
    }

    /** Once set, {@link FakeRelayController} stops auto-replying PONG to PING — simulates an unresponsive relay. */
    void dropAllPings() {
        dropAllPings = true;
    }

    int connectAttempts() {
        return connectAttempts.get();
    }

    FakeRelayController currentController() {
        return current;
    }

    /** Simulates the currently-connected controller's stream dying (crash, network loss, relay restart). */
    void simulateDisconnect() {
        replayDisconnectFor(current);
    }

    /** Fires onDisconnected for a specific (possibly no-longer-current) controller — for testing stale-signal handling. */
    void replayDisconnectFor(FakeRelayController controller) {
        RelayEventHandler handler = currentHandler;
        if (handler != null && controller != null) {
            handler.onDisconnected(RELAY_PEER_ID, controller);
        }
    }

    /** Simulates a DELIVER (or any non-keepalive) frame arriving from the relay. */
    void deliverFrame(RelayFrame frame) {
        RelayEventHandler handler = currentHandler;
        if (handler != null) {
            handler.onFrame(RELAY_PEER_ID, frame);
        }
    }

    @Override
    public RelayController connectToRelay(String relayMultiaddr, RelayEventHandler onEvent) throws Exception {
        connectAttempts.incrementAndGet();
        if (remainingFailures > 0) {
            remainingFailures--;
            throw new Exception("simulated relay-connect failure");
        }
        currentHandler = onEvent;
        FakeRelayController controller = new FakeRelayController(dropAllPings, onEvent, RELAY_PEER_ID);
        current = controller;
        onEvent.onConnected(RELAY_PEER_ID, controller);
        return controller;
    }

    // Nothing below this line is exercised by RelaySessionTest -- present only because
    // implementing the interface requires it.

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler, DiscoveryRequestHandler discoveryRequestHandler) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public void stop() {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public String[] listenAddresses() {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public long pingPeer(String multiaddr) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public void sendEnvelope(String multiaddr, byte[] data) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public void sendEnvelope(String multiaddr, byte[] data, long timeoutMillis) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    @Override
    public DiscoveryController connectToDiscovery(String discoveryMultiaddr) {
        throw new UnsupportedOperationException("not exercised by RelaySessionTest");
    }

    /** The fake RelayController handed back by {@link #connectToRelay} — records sends, auto-replies to PING unless told not to. */
    static final class FakeRelayController implements RelayController {
        private final boolean dropPings;
        private final RelayEventHandler handlerForPongReplies;
        private final PeerId relayPeerId;
        private final List<RelayFrame> sentFrames = new CopyOnWriteArrayList<>();
        private volatile boolean closeCalled = false;
        private volatile boolean failNextSend = false;

        FakeRelayController(boolean dropPings, RelayEventHandler handlerForPongReplies, PeerId relayPeerId) {
            this.dropPings = dropPings;
            this.handlerForPongReplies = handlerForPongReplies;
            this.relayPeerId = relayPeerId;
        }

        /** The next call to {@link #send} throws, simulating a dead-but-not-yet-detected connection. */
        void failNextSend() {
            failNextSend = true;
        }

        List<RelayFrame> sentFrames() {
            return List.copyOf(sentFrames);
        }

        boolean closeCalled() {
            return closeCalled;
        }

        @Override
        public void send(RelayFrame frame) {
            if (failNextSend) {
                failNextSend = false;
                throw new RuntimeException("simulated send failure");
            }
            sentFrames.add(frame);
            if (frame.type() == RelayFrameType.PING && !dropPings) {
                // Mirrors RelayProtocol's real transparent PING->PONG echo (see that class) --
                // RelaySessionTest is testing RelaySession's own state machine, not re-verifying
                // that a real relay answers pings, so this fake just does it directly.
                handlerForPongReplies.onFrame(relayPeerId, new RelayFrame(RelayFrameType.PONG, "", frame.payload()));
            }
        }

        @Override
        public void close() {
            closeCalled = true;
        }
    }
}
