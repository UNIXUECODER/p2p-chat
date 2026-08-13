package com.p2pchat.network;

import io.libp2p.core.Host;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.crypto.keys.Ed25519Kt;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;

import java.util.concurrent.TimeUnit;

public class Libp2pNetworkService implements PeerNetworkService {

    private Host host;

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage) {
        try {
            host = new HostBuilder()
                    .protocol(new Ping(), new EnvelopeBinding(onEnvelopeMessage))
                    .listen("/ip4/0.0.0.0/tcp/" + listenPort)
                    // HostBuilder.build() normally calls identity.random(keyType) internally,
                    // which is why the peer ID was different on every run. builderModifier
                    // is the escape hatch into the underlying BuilderJ/IdentityBuilder — its
                    // public `factory` property is what .random() itself sets, so overriding
                    // it here makes the SAME persisted seed the source of the peer ID instead.
                    .builderModifier(b -> b.getIdentity().setFactory(
                            () -> Ed25519Kt.unmarshalEd25519PrivateKey(identityKeySeed)
                    ))
                    .build();
            host.start().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start libp2p host", e);
        }
    }

    @Override
    public void stop() throws Exception {
        if (host != null) {
            host.stop().get();
        }
    }

    @Override
    public String[] listenAddresses() {
        // Host.listenAddresses() already appends the PeerId to each address per its own
        // documented contract ("...with PeerId appended") — do not append it again here.
        return host.listenAddresses().stream()
                .map(Object::toString)
                .toArray(String[]::new);
    }

    @Override
    public long pingPeer(String multiaddrString) throws Exception {
        Multiaddr address = Multiaddr.fromString(multiaddrString);
        PingController pinger = new Ping().dial(host, address).getController().get();
        return pinger.ping().get();
    }

    @Override
    public void sendEnvelope(String multiaddrString, byte[] data) throws Exception {
        Multiaddr address = Multiaddr.fromString(multiaddrString);
        // No-op callback here: this binding instance is only used to dial out.
        // Anything the other side sends back on this particular stream isn't
        // handled — fine for M2b's one-shot proof, revisit for real two-way flow (M2c).
        EnvelopeController controller = new EnvelopeBinding((sender, incomingData) -> { })
                .dial(host, address)
                .getController()
                .get();
        controller.send(data);
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler) {
        // Deliberately independent of the 3-arg start() above, rather than having
        // one delegate to the other — keeps that already-verified method (M1–M2c)
        // completely untouched rather than risking a subtle behavior change to it.
        try {
            host = new HostBuilder()
                    .protocol(new Ping(), new EnvelopeBinding(onEnvelopeMessage), new RelayBinding(relayEventHandler))
                    .listen("/ip4/0.0.0.0/tcp/" + listenPort)
                    .builderModifier(b -> b.getIdentity().setFactory(
                            () -> Ed25519Kt.unmarshalEd25519PrivateKey(identityKeySeed)
                    ))
                    .build();
            host.start().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start libp2p host", e);
        }
    }

    @Override
    public RelayController connectToRelay(String relayMultiaddrString, RelayEventHandler onEvent) throws Exception {
        Multiaddr address = Multiaddr.fromString(relayMultiaddrString);
        return new RelayBinding(onEvent).dial(host, address).getController().get();
    }

    @Override
    public void sendEnvelope(String multiaddrString, byte[] data, long timeoutMillis) throws Exception {
        Multiaddr address = Multiaddr.fromString(multiaddrString);
        // getController() returns a genuine java.util.concurrent.CompletableFuture<T>
        // (confirmed against the real StreamPromise<T> source, not assumed) — the timed
        // get(long, TimeUnit) overload here is guaranteed by the JDK's own Future
        // contract, not something jvm-libp2p had to add support for specially.
        EnvelopeController controller = new EnvelopeBinding((sender, incomingData) -> { })
                .dial(host, address)
                .getController()
                .get(timeoutMillis, TimeUnit.MILLISECONDS);
        controller.send(data);
    }

    @Override
    public DiscoveryController connectToDiscovery(String discoveryMultiaddrString) throws Exception {
        Multiaddr address = Multiaddr.fromString(discoveryMultiaddrString);
        // The requestHandler passed here is never actually used — DiscoveryProtocol's
        // Initiator role (which is what dialing out gets you) doesn't call it at all,
        // only the Responder role does. A no-op is passed only because the constructor
        // requires something; relay-server passes a real one for its Responder role.
        DiscoveryRequestHandler unusedOnInitiatorSide = new DiscoveryRequestHandler() {
            @Override
            public void onPublish(com.p2pchat.model.PeerId publisher, byte[] payload) {
            }

            @Override
            public byte[] onLookup(String targetPeerId) {
                return null;
            }
        };
        return new DiscoveryBinding(unusedOnInitiatorSide).dial(host, address).getController().get();
    }

    @Override
    public void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
                       RelayEventHandler relayEventHandler, DiscoveryRequestHandler discoveryRequestHandler) {
        // Deliberately independent of the 3-arg and 4-arg start() above, rather than
        // having one delegate to another — keeps those already-verified methods
        // (M1–M3b) completely untouched rather than risking a subtle behavior change.
        try {
            host = new HostBuilder()
                    .protocol(new Ping(), new EnvelopeBinding(onEnvelopeMessage), new RelayBinding(relayEventHandler),
                            new DiscoveryBinding(discoveryRequestHandler))
                    .listen("/ip4/0.0.0.0/tcp/" + listenPort)
                    .builderModifier(b -> b.getIdentity().setFactory(
                            () -> Ed25519Kt.unmarshalEd25519PrivateKey(identityKeySeed)
                    ))
                    .build();
            host.start().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start libp2p host", e);
        }
    }
}
