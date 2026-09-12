package com.p2pchat.network;

import com.p2pchat.model.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pre-m6h-hardening-plan.md, Track A / A-1. Every scenario here runs against a real {@link
 * RelaySession} — only the network transport is fake, via {@link FakeRelayNetwork}, the same
 * "core-network has no jvm-libp2p type in its own signatures" property {@code
 * OutboundMessageServiceTest} already established for {@code ConnectionStrategy}.
 *
 * <p>Every timing parameter passed to {@link #newSession} is in the tens-of-milliseconds range,
 * deliberately far below production defaults (30s backoff cap, 60s keepalive) — these tests need
 * to observe several reconnect/keepalive cycles without actually waiting on delays sized for a
 * real network. {@link #waitUntil} is this project's own established pattern for asserting on
 * eventual async state (see {@code SessionManagerReceivePipelineTest}), not a new one.
 */
class RelaySessionTest {

    private final FakeRelayNetwork network = new FakeRelayNetwork();
    private RelaySession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void connectSucceedsImmediatelyWhenRelayIsReachable() {
        session = newSession();
        session.connect();

        assertThat(session.isConnected()).isTrue();
        assertThat(network.connectAttempts()).isEqualTo(1);
    }

    @Test
    void sendDelegatesToTheUnderlyingControllerWhileConnected() {
        session = newSession();
        session.connect();

        boolean sent = session.send("bob-peer-id", "hello".getBytes());

        assertThat(sent).isTrue();
        List<RelayFrame> sentFrames = network.currentController().sentFrames();
        assertThat(sentFrames).hasSize(1);
        assertThat(sentFrames.get(0).isForwardRequest()).isTrue();
        assertThat(sentFrames.get(0).peerId()).isEqualTo("bob-peer-id");
    }

    @Test
    void sendReturnsFalseWithoutThrowingWhenNeverConnected() {
        network.failNextConnectAttempts(Integer.MAX_VALUE); // never succeeds
        session = newSession();
        session.connect();

        assertThat(session.send("bob", "hi".getBytes())).isFalse();
    }

    @Test
    void reconnectsAfterInitialConnectFailuresUsingBackoff() throws InterruptedException {
        network.failNextConnectAttempts(2); // first two attempts fail, third succeeds
        session = newSession();
        session.connect();

        assertThat(session.isConnected()).isFalse(); // the synchronous first attempt failed

        waitUntil(() -> session.isConnected(), Duration.ofSeconds(2));
        assertThat(network.connectAttempts()).isEqualTo(3);
    }

    @Test
    void onDisconnectedTriggersAnAutomaticReconnect() throws InterruptedException {
        session = newSession();
        session.connect();
        assertThat(session.isConnected()).isTrue();

        network.simulateDisconnect();
        assertThat(session.isConnected()).isFalse();

        waitUntil(() -> session.isConnected(), Duration.ofSeconds(2));
        assertThat(network.connectAttempts()).isEqualTo(2);
    }

    @Test
    void aSendFailureIsTreatedAsADisconnectAndTriggersReconnect() throws InterruptedException {
        session = newSession();
        session.connect();
        network.currentController().failNextSend();

        assertThat(session.send("bob", "hi".getBytes())).isFalse();
        assertThat(session.isConnected()).isFalse();

        waitUntil(() -> session.isConnected(), Duration.ofSeconds(2));
        assertThat(network.connectAttempts()).isEqualTo(2);
    }

    @Test
    void staleDisconnectSignalForAReplacedControllerIsIgnored() throws InterruptedException {
        session = newSession();
        session.connect();
        FakeRelayNetwork.FakeRelayController firstController = network.currentController();

        network.simulateDisconnect();
        waitUntil(() -> session.isConnected(), Duration.ofSeconds(2));
        FakeRelayNetwork.FakeRelayController secondController = network.currentController();
        assertThat(secondController).isNotSameAs(firstController);

        // A disconnect signal for the OLD connection arriving late, after the reconnect already
        // finished, must not tear down the current (new) one.
        network.replayDisconnectFor(firstController);

        assertThat(session.isConnected()).isTrue();
        assertThat(network.connectAttempts()).isEqualTo(2); // no extra reconnect triggered
    }

    @Test
    void keepaliveSurvivesWhenPingsAreAnswered() throws InterruptedException {
        session = newSession();
        session.connect();

        waitUntil(() -> !network.currentController().sentFrames().isEmpty(), Duration.ofSeconds(2));
        assertThat(network.currentController().sentFrames().get(0).type()).isEqualTo(RelayFrameType.PING);

        // FakeRelayController auto-replies PONG by default -- give the pong-timeout task time to
        // have fired if it were going to, proving it did NOT tear the connection down.
        Thread.sleep(150);
        assertThat(session.isConnected()).isTrue();
        assertThat(network.connectAttempts()).isEqualTo(1); // never reconnected
    }

    @Test
    void keepaliveWithNoPongEventuallyForcesAReconnect() throws InterruptedException {
        network.dropAllPings(); // simulates a relay that stopped answering
        session = newSession();
        session.connect();

        // No send() ever fails here -- RelaySession's own keepalive is what has to notice.
        waitUntil(() -> network.connectAttempts() >= 2, Duration.ofSeconds(3));
    }

    @Test
    void nonKeepaliveFramesAreForwardedToTheDownstreamHandler() {
        List<RelayFrame> received = new CopyOnWriteArrayList<>();
        RelayEventHandler downstream = new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }
            @Override public void onFrame(PeerId sender, RelayFrame frame) { received.add(frame); }
            @Override public void onDisconnected(PeerId peerId, RelayController controller) { }
        };
        session = new RelaySession(network, "/ip4/127.0.0.1/tcp/9999/p2p/relay", downstream, null,
                Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofMillis(150), Duration.ofMillis(60));
        session.connect();

        network.deliverFrame(new RelayFrame(false, "alice", "hi".getBytes()));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).peerId()).isEqualTo("alice");
    }

    @Test
    void onConnectivityChangedFiresOnConnectDisconnectAndReconnect() throws InterruptedException {
        AtomicInteger callbackCount = new AtomicInteger(0);
        session = new RelaySession(network, "/ip4/127.0.0.1/tcp/9999/p2p/relay", null, callbackCount::incrementAndGet,
                Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofMillis(150), Duration.ofMillis(60));

        session.connect();
        assertThat(callbackCount.get()).isEqualTo(1); // connected

        network.simulateDisconnect();
        assertThat(callbackCount.get()).isEqualTo(2); // disconnected

        waitUntil(() -> callbackCount.get() >= 3, Duration.ofSeconds(2)); // reconnected
    }

    @Test
    void closeStopsReconnectingAndReleasesTheController() {
        session = newSession();
        session.connect();
        FakeRelayNetwork.FakeRelayController controller = network.currentController();

        session.close();

        assertThat(controller.closeCalled()).isTrue();
        network.simulateDisconnect(); // even if this still fires, no reconnect must follow
        assertThat(network.connectAttempts()).isEqualTo(1);
    }

    @Test
    void connectIsIdempotent() {
        session = newSession();
        session.connect();
        session.connect();
        session.connect();

        assertThat(network.connectAttempts()).isEqualTo(1);
    }

    private RelaySession newSession() {
        return new RelaySession(network, "/ip4/127.0.0.1/tcp/9999/p2p/relay", null, null,
                Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofMillis(150), Duration.ofMillis(60));
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("condition met within " + timeout).isTrue();
    }
}
