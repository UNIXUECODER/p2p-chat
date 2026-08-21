package com.p2pchat.daemon.send;

import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.OnEnvelopeMessage;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A controllable {@link PeerNetworkService} double — no jvm-libp2p involved, since {@code
 * ConnectionStrategy}/{@code OutboundMessageService} only ever depend on this interface, never
 * on {@code Libp2pNetworkService} itself. This is what makes real, executed (not hand-traced)
 * coverage of M6b's orchestration logic possible in a sandbox that can't resolve jvm-libp2p:
 * the actual network transport isn't what M6b adds, so it doesn't need to be real for this.
 */
final class FakePeerNetworkService implements PeerNetworkService {

    private boolean directSucceeds = true;
    private boolean directHangsForever = false;
    private boolean relaySucceeds = true;
    private boolean relayHangsForever = false;
    private final List<String> directSendAttempts = new CopyOnWriteArrayList<>();
    private final List<RelayFrame> relaySendAttempts = new CopyOnWriteArrayList<>();

    void directFails() {
        directSucceeds = false;
    }

    void directHangs() {
        directHangsForever = true;
    }

    void relayFails() {
        relaySucceeds = false;
    }

    void relayHangs() {
        relayHangsForever = true;
    }

    List<String> directSendAttempts() {
        return new ArrayList<>(directSendAttempts);
    }

    List<RelayFrame> relaySendAttempts() {
        return new ArrayList<>(relaySendAttempts);
    }

    @Override
    public void sendEnvelope(String multiaddr, byte[] data, long timeoutMillis) throws Exception {
        directSendAttempts.add(multiaddr);
        if (directHangsForever) {
            Thread.sleep(Long.MAX_VALUE);
        }
        if (!directSucceeds) {
            throw new Exception("simulated direct-send failure");
        }
    }

    @Override
    public RelayController connectToRelay(String relayMultiaddr, RelayEventHandler onEvent) throws Exception {
        if (relayHangsForever) {
            Thread.sleep(Long.MAX_VALUE);
        }
        if (!relaySucceeds) {
            throw new Exception("simulated relay-connect failure");
        }
        return frame -> relaySendAttempts.add(frame);
    }

    // Nothing below this line is exercised by ConnectionStrategy/OutboundMessageService's tests
    // -- present only because implementing the interface requires it.

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public void stop() {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public String[] listenAddresses() {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public long pingPeer(String multiaddr) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public void sendEnvelope(String multiaddr, byte[] data) {
        throw new UnsupportedOperationException("not exercised by these tests -- the 3-arg overload is");
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public DiscoveryController connectToDiscovery(String discoveryMultiaddr) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler, com.p2pchat.network.DiscoveryRequestHandler discoveryRequestHandler) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }
}
