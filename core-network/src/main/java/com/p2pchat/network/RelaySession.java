package com.p2pchat.network;

import com.p2pchat.model.PeerId;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * pre-m6h-hardening-plan.md, Track A / A-1: a relay connection this session owns for its whole
 * lifetime, instead of the pre-A-1 behaviour where {@link ConnectionStrategy} dialed a brand-new
 * one per send and never closed it (finding A-2 in that plan). This is the piece that makes
 * relay-as-real-transport actually usable: two peers with no direct path can only hold a
 * conversation through a relay if the relay connection itself survives being idle for a while and
 * reconnects when the relay (or the network path to it) drops.
 *
 * <p><b>What this owns:</b>
 * <ul>
 *   <li><b>Reconnect with backoff.</b> Exponential, starting at {@code initialBackoff}, capped at
 *   {@code maxBackoff} (30s by default — the audit's own stated cap), with "equal jitter"
 *   (guarantees at least half the computed delay, randomizes the rest) so that if a relay comes
 *   back up with many clients waiting, they don't all reconnect in the same instant.</li>
 *   <li><b>Application-level keepalive.</b> TCP alone doesn't reveal a NAT silently dropping its
 *   mapping — the audit's own reasoning: "NAT mapping timeouts for TCP are commonly as low as
 *   5-15 minutes... the daemon will believe it is reachable when it is not." Every {@code
 *   keepaliveInterval} (60s by default), this session sends a {@code PING} with a fresh nonce; if
 *   no matching {@code PONG} arrives within {@code pongTimeout} (10s by default), the connection
 *   is declared dead and reconnect kicks in immediately, rather than waiting for the next send
 *   attempt to fail.</li>
 * </ul>
 *
 * <p><b>What this deliberately does not do</b> (A-2/A-3, not this class):
 * <ul>
 *   <li>No store-and-forward. A {@code send()} while disconnected simply returns {@code false} —
 *   this session has no queue of "send once reconnected." {@code ConnectionStrategy} already
 *   treats that as {@code ConnectivityStatus.UNREACHABLE}, same as any other send failure.</li>
 *   <li>No inbound {@code DELIVER} routing into a daemon's decrypt/dispatch pipeline — this class
 *   forwards non-keepalive frames to whatever {@code downstreamHandler} the caller supplied, and
 *   stops there. Wiring that handler to {@code SessionManager}'s actual receive pipeline is A-2's
 *   job, named here as a deliberate seam, not a silent gap.</li>
 * </ul>
 *
 * <p><b>Thread-safety.</b> All mutable state is either an atomic or touched only from this
 * session's own single-threaded scheduler, following this project's own established pattern for
 * exactly this kind of race (see architecture-spec.md's note on the M3c {@code
 * AtomicReference.compareAndSet} fix). In particular, {@link #handleDisconnect} always
 * compare-and-swaps out the specific stale controller it was told about — never just "the current
 * one" — so a disconnect signal that arrives late (after a fast reconnect already replaced it)
 * can't tear down a connection that isn't actually the one that failed.
 */
public final class RelaySession implements AutoCloseable {

    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration DEFAULT_KEEPALIVE_INTERVAL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_PONG_TIMEOUT = Duration.ofSeconds(10);

    private static final long NO_PING_OUTSTANDING = Long.MIN_VALUE;

    private static final RelayEventHandler NO_OP_DOWNSTREAM_HANDLER = new RelayEventHandler() {
        @Override public void onConnected(PeerId peerId, RelayController controller) { }
        @Override public void onFrame(PeerId sender, RelayFrame frame) { }
        @Override public void onDisconnected(PeerId peerId, RelayController controller) { }
    };

    private final PeerNetworkService network;
    private final String relayMultiaddr;
    private final RelayEventHandler downstreamHandler;
    private final Runnable onConnectivityChanged;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration keepaliveInterval;
    private final Duration pongTimeout;
    private final ScheduledExecutorService scheduler;
    private final RelayEventHandler internalHandler = new InternalHandler();

    private final AtomicReference<RelayController> controllerRef = new AtomicReference<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong nonceGenerator = new AtomicLong(0);
    private final AtomicLong outstandingPingNonce = new AtomicLong(NO_PING_OUTSTANDING);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connectCalled = new AtomicBoolean(false);

    private volatile PeerId relayPeerId;

    /** Sane defaults, no downstream frame handling (A-2 isn't wired yet) and no connectivity callback. */
    public RelaySession(PeerNetworkService network, String relayMultiaddr) {
        this(network, relayMultiaddr, null, null);
    }

    /**
     * @param downstreamHandler     receives every non-keepalive frame (i.e. {@code DELIVER}) this
     *                              session's connection gets, or {@code null} if the caller has
     *                              nothing to do with them yet
     * @param onConnectivityChanged invoked (off whatever thread noticed) whenever this session
     *                              connects or disconnects, or {@code null} if the caller doesn't
     *                              need to know — {@code SessionManager} is expected to pass
     *                              {@code () -> emit(listener::onNetworkStatusChanged)}, reusing
     *                              the existing bare "something worth re-checking happened" signal
     *                              rather than this class depending on {@code DaemonEventListener}
     *                              directly (core-network cannot depend on node-daemon)
     */
    public RelaySession(PeerNetworkService network, String relayMultiaddr,
                         RelayEventHandler downstreamHandler, Runnable onConnectivityChanged) {
        this(network, relayMultiaddr, downstreamHandler, onConnectivityChanged,
                DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_KEEPALIVE_INTERVAL, DEFAULT_PONG_TIMEOUT);
    }

    /** Full constructor — the timing parameters exist mainly so tests don't have to wait 30 real seconds for a backoff cap. */
    public RelaySession(PeerNetworkService network, String relayMultiaddr,
                         RelayEventHandler downstreamHandler, Runnable onConnectivityChanged,
                         Duration initialBackoff, Duration maxBackoff,
                         Duration keepaliveInterval, Duration pongTimeout) {
        this.network = network;
        this.relayMultiaddr = relayMultiaddr;
        this.downstreamHandler = downstreamHandler != null ? downstreamHandler : NO_OP_DOWNSTREAM_HANDLER;
        this.onConnectivityChanged = onConnectivityChanged != null ? onConnectivityChanged : () -> { };
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.keepaliveInterval = keepaliveInterval;
        this.pongTimeout = pongTimeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "relay-session");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts this session: connects now (blocking briefly on this call, same as every relay demo
     * Main up through M6g already does) and, from then on, maintains the connection in the
     * background for as long as this session stays open — reconnecting on failure/drop rather
     * than requiring the caller to notice and retry. Never throws: a failed first attempt falls
     * straight into the same backoff-and-retry loop a later drop would, rather than failing
     * daemon startup over a relay that happens to be down at that moment. Idempotent — a second
     * call is a no-op.
     */
    public void connect() {
        if (!connectCalled.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::keepaliveTick,
                keepaliveInterval.toMillis(), keepaliveInterval.toMillis(), TimeUnit.MILLISECONDS);
        attemptConnect();
    }

    /**
     * Sends a FORWARD frame asking the relay to deliver {@code payload} to {@code targetPeerId}.
     * Returns {@code false} (never throws) when not currently connected, or when the send itself
     * fails — either way, the caller (see {@code ConnectionStrategy}) treats that as this relay
     * path being unavailable right now, same as any other relay failure. A send failure also
     * triggers this session's own disconnect handling immediately, rather than waiting for the
     * next keepalive cycle to notice.
     */
    public boolean send(String targetPeerId, byte[] payload) {
        RelayController current = controllerRef.get();
        if (current == null) {
            return false;
        }
        try {
            current.send(new RelayFrame(true, targetPeerId, payload));
            return true;
        } catch (Exception e) {
            handleDisconnect(current);
            return false;
        }
    }

    public boolean isConnected() {
        return controllerRef.get() != null;
    }

    /** The relay's own libp2p peer ID, once connected at least once — {@code null} before that. */
    public PeerId relayPeerId() {
        return relayPeerId;
    }

    /** Exposes the internal RelayEventHandler so host-level stream events route into this session. */
    public RelayEventHandler eventHandler() {
        return internalHandler;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        RelayController current = controllerRef.getAndSet(null);
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // best-effort on the way out
            }
        }
    }

    @Override
    public String toString() {
        return "RelaySession{relay=" + relayMultiaddr + ", connected=" + isConnected() + "}";
    }

    private void attemptConnect() {
        if (closed.get()) {
            return;
        }
        try {
            // InternalHandler.onConnected fires synchronously as part of this call returning
            // (RelayProtocol doesn't hand back a controller until onActivated already ran) — it's
            // what actually records the new controller and resets backoff state, so there's
            // nothing left to do here on success.
            network.connectToRelay(relayMultiaddr, internalHandler);
        } catch (Exception e) {
            System.err.println("[relay-session] connect attempt to " + relayMultiaddr + " failed: " + e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        int attempt = reconnectAttempts.getAndIncrement();
        long exponentialMillis = Math.min(maxBackoff.toMillis(),
                initialBackoff.toMillis() * (1L << Math.min(attempt, 30)));
        long halfMillis = exponentialMillis / 2;
        // Equal jitter: always wait at least half the computed backoff, randomize the rest --
        // avoids every disconnected client reconnecting in the same instant a relay comes back,
        // without ever waiting less than half the intended delay.
        long jitteredMillis = halfMillis + ThreadLocalRandom.current().nextLong(halfMillis + 1);
        try {
            scheduler.schedule(this::attemptConnect, jitteredMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // close() raced with this -- nothing left to retry
        }
    }

    private void handleDisconnect(RelayController staleController) {
        if (closed.get() || staleController == null) {
            return;
        }
        // Only tear down if staleController is still the current one -- a disconnect signal that
        // arrives late (after a fast reconnect already installed a newer controller) must not
        // undo that newer connection. See class Javadoc.
        if (!controllerRef.compareAndSet(staleController, null)) {
            return;
        }
        outstandingPingNonce.set(NO_PING_OUTSTANDING);
        try {
            staleController.close();
        } catch (Exception ignored) {
            // it's already dead from this session's point of view
        }
        notifyConnectivityChanged();
        scheduleReconnect();
    }

    private void keepaliveTick() {
        if (closed.get()) {
            return;
        }
        RelayController current = controllerRef.get();
        if (current == null) {
            return; // not connected right now -- a reconnect is already pending or in flight
        }
        if (outstandingPingNonce.get() != NO_PING_OUTSTANDING) {
            // A previous ping's own deadline check (see checkPongReceived) is what's actually
            // responsible for noticing it went unanswered -- reaching this branch would mean that
            // deadline hasn't fired yet despite a full keepalive interval passing, which shouldn't
            // happen given pongTimeout < keepaliveInterval. Handled defensively rather than
            // overwriting a still-pending nonce.
            return;
        }
        long nonce = nonceGenerator.incrementAndGet();
        outstandingPingNonce.set(nonce);
        try {
            current.send(new RelayFrame(RelayFrameType.PING, "", encodeNonce(nonce)));
        } catch (Exception e) {
            handleDisconnect(current);
            return;
        }
        scheduler.schedule(() -> checkPongReceived(current, nonce), pongTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void checkPongReceived(RelayController controllerAtPingTime, long nonce) {
        // If this CAS succeeds, the nonce was STILL outstanding at the deadline -- no matching
        // PONG ever cleared it (see InternalHandler.onFrame), so the relay is unresponsive.
        // If it fails, a PONG already cleared it first -- healthy connection, nothing to do.
        if (outstandingPingNonce.compareAndSet(nonce, NO_PING_OUTSTANDING)) {
            handleDisconnect(controllerAtPingTime);
        }
    }

    private void notifyConnectivityChanged() {
        try {
            onConnectivityChanged.run();
        } catch (Exception e) {
            System.err.println("[relay-session] onConnectivityChanged callback threw: " + e);
        }
    }

    private static byte[] encodeNonce(long nonce) {
        return ByteBuffer.allocate(Long.BYTES).putLong(nonce).array();
    }

    /** Malformed/foreign payload decodes to the sentinel, which can never match a real outstanding nonce -- ignored, not thrown. */
    private static long decodeNonce(byte[] payload) {
        if (payload.length < Long.BYTES) {
            return NO_PING_OUTSTANDING;
        }
        return ByteBuffer.wrap(payload).getLong();
    }

    private final class InternalHandler implements RelayEventHandler {
        @Override
        public void onConnected(PeerId peerId, RelayController newController) {
            relayPeerId = peerId;
            controllerRef.set(newController);
            reconnectAttempts.set(0);
            outstandingPingNonce.set(NO_PING_OUTSTANDING);
            notifyConnectivityChanged();
        }

        @Override
        public void onFrame(PeerId sender, RelayFrame frame) {
            if (frame.type() == RelayFrameType.PONG) {
                long nonce = decodeNonce(frame.payload());
                outstandingPingNonce.compareAndSet(nonce, NO_PING_OUTSTANDING);
                return;
            }
            downstreamHandler.onFrame(sender, frame);
        }

        @Override
        public void onDisconnected(PeerId peerId, RelayController controller) {
            handleDisconnect(controller);
        }
    }
}
