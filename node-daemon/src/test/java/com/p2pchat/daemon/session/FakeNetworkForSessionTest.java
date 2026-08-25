package com.p2pchat.daemon.session;

import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.network.OnEnvelopeMessage;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal {@link PeerNetworkService} double for testing {@link SessionManager}'s receive
 * pipeline in isolation — only {@link #sendEnvelope} needs to actually work, since {@link
 * SessionManager#handleDecryptedPlaintext} (called directly by the test, bypassing {@code
 * handleInboundEnvelope}'s decrypt step entirely) reaches it only via the auto-delivery-receipt
 * path. Every other method is genuinely unused by that path and throws if reached.
 */
final class FakeNetworkForSessionTest implements PeerNetworkService {

    private final List<String> sentTo = new CopyOnWriteArrayList<>();

    List<String> sentTo() {
        return List.copyOf(sentTo);
    }

    @Override
    public void sendEnvelope(String multiaddr, byte[] data, long timeoutMillis) {
        sentTo.add(multiaddr);
    }

    @Override
    public void sendEnvelope(String multiaddr, byte[] data) {
        sentTo.add(multiaddr);
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler, DiscoveryRequestHandler discoveryRequestHandler) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public void stop() {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public String[] listenAddresses() {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public long pingPeer(String multiaddr) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public RelayController connectToRelay(String relayMultiaddr, RelayEventHandler onEvent) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }

    @Override
    public DiscoveryController connectToDiscovery(String discoveryMultiaddr) {
        throw new UnsupportedOperationException("not exercised by the receive-pipeline test");
    }
}
