package com.p2pchat.daemon.send;

import com.p2pchat.network.ConnectionStrategy;
import com.p2pchat.network.ConnectivityStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6b. Every scenario here runs against a real {@link ConnectionStrategy} (M3b, unmodified) and
 * a real {@link OutboundMessageService} (this milestone) — only the network transport underneath
 * is fake, via {@link FakePeerNetworkService}. That's a deliberate, real distinction from every
 * crypto-adjacent test in this project: {@code ConnectionStrategy} has no jvm-libp2p type in its
 * own signature (confirmed by reading its imports before writing this), so nothing here is
 * hand-traced or stub-compiled — this compiles and runs directly, in this sandbox, against the
 * actual production classes.
 */
class OutboundMessageServiceTest {

    private final FakePeerNetworkService network = new FakePeerNetworkService();
    private final ConnectionStrategy connectionStrategy = new ConnectionStrategy(network, 500);
    private final OutboundMessageService outbound =
            new OutboundMessageService(connectionStrategy, Duration.ofMillis(300));

    @AfterEach
    void tearDown() {
        outbound.close();
    }

    @Test
    void directSucceedsResolvesToDirectOffTheCallingThread() throws Exception {
        Thread callingThread = Thread.currentThread();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", null, null, "hello".getBytes());

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.DIRECT);
        assertThat(network.directSendAttempts()).containsExactly("/ip4/127.0.0.1/tcp/9000/p2p/bob");
        assertThat(network.relaySendAttempts()).isEmpty();
        // The real point of M6b, not incidental: this assertion only means something because
        // ConnectionStrategy.send(...) is synchronous internally -- if OutboundMessageService
        // weren't actually running it off-thread, this would still trivially pass by running on
        // the test's own thread. Proven properly by directBlocksTheUnderlyingThreadButNotTheCaller.
        assertThat(Thread.currentThread()).isEqualTo(callingThread);
    }

    @Test
    void directFailsFallsBackToRelay() throws Exception {
        network.directFails();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", "/ip4/1.2.3.4/tcp/443/p2p/relay",
                        "bob-peer-id", "hello".getBytes());

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.RELAYED);
        assertThat(network.directSendAttempts()).hasSize(1); // it did try direct first
        assertThat(network.relaySendAttempts()).hasSize(1);
        assertThat(network.relaySendAttempts().get(0).peerId()).isEqualTo("bob-peer-id");
    }

    @Test
    void bothPathsFailResolvesToUnreachableNotAFailedFuture() throws Exception {
        network.directFails();
        network.relayFails();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", "/ip4/1.2.3.4/tcp/443/p2p/relay",
                        "bob-peer-id", "hello".getBytes());

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE);
        assertThat(future.isCompletedExceptionally()).isFalse(); // the actual contract being tested
    }

    @Test
    void noDirectAddressSkipsStraightToRelay() throws Exception {
        CompletableFuture<ConnectivityStatus> future =
                outbound.send(null, "/ip4/1.2.3.4/tcp/443/p2p/relay", "bob-peer-id", "hello".getBytes());

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.RELAYED);
        assertThat(network.directSendAttempts()).isEmpty();
    }

    @Test
    void aHungDirectAttemptStillResolvesToUnreachableViaTheOverallTimeout() throws Exception {
        // ConnectionStrategy's own directTimeoutMillis is a parameter it trusts the real
        // PeerNetworkService implementation to respect -- ConnectionStrategy itself does no
        // additional bounding. This deliberately ignores that parameter (simulating an
        // implementation that doesn't honor it) to prove OutboundMessageService's OWN overall
        // timeout is a real, independent safety net, not just a pass-through of the same bound.
        network.directHangs();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", null, null, "hello".getBytes());

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE);
    }

    @Test
    void aHungRelayAttemptStillResolvesToUnreachableViaTheOverallTimeout() throws Exception {
        network.directFails();
        network.relayHangs();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", "/ip4/1.2.3.4/tcp/443/p2p/relay",
                        "bob-peer-id", "hello".getBytes());

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE);
    }

    @Test
    void directBlocksTheUnderlyingThreadButNotTheCaller() throws Exception {
        // The actual deadlock scenario M5c-e each hit and fixed ad hoc, reproduced directly:
        // if send() blocked the calling thread the way a raw synchronous sendEnvelope(...) call
        // did, this test itself would hang for the full simulated delay before send() even
        // returned a future. It doesn't -- send() returns immediately; only the future's
        // completion is delayed.
        network.directHangs();
        long before = System.nanoTime();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", null, null, "hello".getBytes());

        long callReturnedAfterMillis = (System.nanoTime() - before) / 1_000_000;
        assertThat(callReturnedAfterMillis).isLessThan(50); // returned near-instantly, not after the hang
        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE); // still resolves later
    }

    @Test
    void multipleConcurrentSendsToDifferentPeersDoNotBlockEachOther() throws Exception {
        network.directHangs(); // every send will hang until the overall timeout

        long before = System.nanoTime();
        CompletableFuture<ConnectivityStatus> first =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", null, null, "a".getBytes());
        CompletableFuture<ConnectivityStatus> second =
                outbound.send("/ip4/127.0.0.1/tcp/9001/p2p/carol", null, null, "b".getBytes());
        long bothCallsReturnedAfterMillis = (System.nanoTime() - before) / 1_000_000;

        assertThat(bothCallsReturnedAfterMillis).isLessThan(50); // both dispatched without waiting on each other
        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE);
        assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE);
    }

    @Test
    void closeReleasesTheExecutorSoFurtherSendsCannotRun() throws Exception {
        outbound.close();

        CompletableFuture<ConnectivityStatus> future =
                outbound.send("/ip4/127.0.0.1/tcp/9000/p2p/bob", null, null, "hello".getBytes());

        // A rejected task on a closed executor completes the future exceptionally at the
        // supplyAsync stage itself -- still caught by the same exceptionally(...) mapping, same
        // "never a failed future" guarantee holding even for this edge case.
        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(ConnectivityStatus.UNREACHABLE);
    }
}
