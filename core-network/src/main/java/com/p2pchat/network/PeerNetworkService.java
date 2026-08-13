package com.p2pchat.network;

/**
 * M1/M1.5/M2b scope: start a node bound to a persistent identity, learn its
 * own address, prove connectivity via ping, and — new in M2b — send and
 * receive arbitrary bytes over our own custom protocol (EnvelopeBinding).
 * This is the transport M2c will run PQXDH handshakes and Double-Ratchet
 * ciphertext over.
 */
public interface PeerNetworkService {

    /**
     * Starts the node listening on the given TCP port (0 = pick a random free port),
     * using the given raw Ed25519 private key seed (from core-identity's
     * IdentityService.rawPrivateKeySeed()) as the node's libp2p identity —
     * so the resulting peer ID is stable across restarts, rather than random.
     * onEnvelopeMessage is invoked whenever another peer sends us bytes over
     * the Envelope protocol.
     */
    void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage);

    /** Stops the node and releases its resources. */
    void stop() throws Exception;

    /** The addresses this node is reachable on, in multiaddr format. */
    String[] listenAddresses();

    /**
     * Dials the given peer (multiaddr format, e.g. "/ip4/192.168.1.5/tcp/9000/p2p/<peer-id>")
     * and pings it once. Returns the round-trip latency in milliseconds.
     * A successful return here is the actual proof-of-connectivity for M1.
     */
    long pingPeer(String multiaddr) throws Exception;

    /**
     * Dials the given peer and sends raw bytes over the Envelope protocol (M2b).
     * A one-shot send: opens a new stream for this call rather than reusing an
     * existing one. Fine for proving the pipe works; a real message flow (M2c)
     * will need to think about stream reuse and delivery guarantees.
     */
    void sendEnvelope(String multiaddr, byte[] data) throws Exception;

    /**
     * M3a: same as the 3-arg start(), but also registers the Relay protocol
     * (RelayBinding) on this host so other peers can dial us over it. A new
     * overload rather than changing the existing signature, so the four
     * working M1–M2c call sites don't need to change at all.
     */
    void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage, RelayEventHandler relayEventHandler);

    /**
     * Dials a relay server and returns a controller for it. Unlike sendEnvelope,
     * the caller is expected to KEEP this controller and the underlying
     * connection alive — that's what lets the relay deliver messages back later.
     */
    RelayController connectToRelay(String relayMultiaddr, RelayEventHandler onEvent) throws Exception;

    /**
     * M3b: same as the 2-arg sendEnvelope, but bounds the dial attempt to the
     * given timeout instead of waiting indefinitely. The timeout is what
     * actually makes "try direct, fall back to relay" (ConnectionStrategy)
     * possible — without it, a dial to an unreachable NAT'd peer can hang far
     * longer than any reasonable fallback wait. A new overload, not a change
     * to the existing 2-arg version, so M2b/M2c's call sites are untouched.
     */
    void sendEnvelope(String multiaddr, byte[] data, long timeoutMillis) throws Exception;

    /**
     * M3c: dials a discovery server and returns a controller for publishing
     * this peer's record or looking up another's. Unlike connectToRelay, no
     * external callback is needed — lookup() is request/response, correlated
     * via its own returned future, not an asynchronous stream of unrelated
     * incoming events the way relay deliveries are.
     */
    DiscoveryController connectToDiscovery(String discoveryMultiaddr) throws Exception;

    /**
     * M3c: same as the 4-arg start(), but also registers the Discovery
     * protocol. Used only by relay-server, which is the only role that needs
     * to ACCEPT incoming discovery connections (publish/lookup requests from
     * clients). Regular peers only ever DIAL a discovery server via
     * connectToDiscovery(), which doesn't require this protocol to be
     * registered on their own host at all — dialing a protocol depends on
     * what the REMOTE peer accepts, not what's registered locally — so
     * ordinary node-daemon call sites don't need to change for this milestone.
     */
    void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
               RelayEventHandler relayEventHandler, DiscoveryRequestHandler discoveryRequestHandler);
}

