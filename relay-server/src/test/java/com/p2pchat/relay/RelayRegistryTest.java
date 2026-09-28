package com.p2pchat.relay;

import com.p2pchat.model.PeerId;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RelayRegistryTest {

    private RelayRegistry registry;
    private FakeRelaySpool fakeSpool;

    @BeforeEach
    void setUp() {
        fakeSpool = new FakeRelaySpool();
        registry = new RelayRegistry(fakeSpool);
    }

    @Test
    void forwardFrameToRegisteredTarget() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");

        AtomicReference<RelayFrame> deliveredFrame = new AtomicReference<>();

        RelayController bobController = new RecordingController(deliveredFrame);

        // Bob connects to the relay server
        registry.onConnected(bob, bobController);

        // Alice sends a FORWARD request targeting Bob
        byte[] payload = "Encrypted message payload".getBytes();
        RelayFrame forwardFrame = new RelayFrame(true, bob.toString(), payload);
        registry.onFrame(alice, forwardFrame);

        // Assert that the relay server forwarded the frame relabeled as a DELIVER frame to Bob
        assertThat(deliveredFrame.get()).isNotNull();
        assertThat(deliveredFrame.get().isForwardRequest()).isFalse(); // Converted to DELIVER frame
        assertThat(deliveredFrame.get().peerId()).isEqualTo(alice.toString()); // Relabeled with Alice as original sender
        assertThat(deliveredFrame.get().payload()).isEqualTo(payload);
    }

    // --- Commit 4 (Track A-3): no route no longer means dropped, it means spooled. ---

    @Test
    void frameForOfflineTargetIsSpooledNotDropped() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId offlineBob = PeerId.of("12D3KooWOfflineBob12345678901234567890123");
        byte[] payload = "Test".getBytes();

        RelayFrame forwardFrame = new RelayFrame(true, offlineBob.toString(), payload);

        // Should not throw or crash when target is offline -- same expectation the old,
        // drop-only version of this test had.
        registry.onFrame(alice, forwardFrame);

        List<SpooledMessage> spooled = fakeSpool.fetchForTarget(offlineBob.toString()).join();
        assertThat(spooled).hasSize(1);
        assertThat(spooled.get(0).senderPeerId()).isEqualTo(alice.toString());
        assertThat(spooled.get(0).payload()).isEqualTo(payload);
    }

    @Test
    void messageRejectedByQuotaIsNotSpooledAndDoesNotThrow() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId offlineBob = PeerId.of("12D3KooWOfflineBob12345678901234567890123");
        fakeSpool.forceNextEnqueueResult(EnqueueResult.REJECTED_QUOTA_EXCEEDED);

        registry.onFrame(alice, new RelayFrame(true, offlineBob.toString(), "Test".getBytes()));

        assertThat(fakeSpool.fetchForTarget(offlineBob.toString()).join()).isEmpty();
    }

    @Test
    void reconnectingPeerReceivesEverySpooledMessageInFifoOrderThenTheySAreCleared() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId carol = PeerId.of("12D3KooWCarol12345678901234567890123456789");
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");

        // Bob is offline for both of these -- both get spooled, from two different senders.
        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "msg1".getBytes()));
        registry.onFrame(carol, new RelayFrame(true, bob.toString(), "msg2".getBytes()));

        RecordingController bobController = new RecordingController(new AtomicReference<>());
        registry.onConnected(bob, bobController);

        List<RelayFrame> delivered = bobController.allSentFrames();
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(0).peerId()).isEqualTo(alice.toString());
        assertThat(delivered.get(0).payload()).isEqualTo("msg1".getBytes());
        assertThat(delivered.get(1).peerId()).isEqualTo(carol.toString());
        assertThat(delivered.get(1).payload()).isEqualTo("msg2".getBytes());

        // Delivered messages are removed from the spool -- a second reconnect wouldn't re-deliver.
        assertThat(fakeSpool.fetchForTarget(bob.toString()).join()).isEmpty();
    }

    @Test
    void newlyConnectedPeerWithNothingSpooledReceivesNothingExtra() {
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");
        RecordingController bobController = new RecordingController(new AtomicReference<>());

        registry.onConnected(bob, bobController);

        assertThat(bobController.allSentFrames()).isEmpty();
    }

    @Test
    void midDrainDisconnectStopsDeliveryAndLeavesRemainingMessagesInSpool() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");

        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "msg1".getBytes()));
        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "msg2".getBytes()));
        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "msg3".getBytes()));

        AtomicReference<RelayController> controllerRef = new AtomicReference<>();
        RelayController disconnectingController = new RelayController() {
            private final List<RelayFrame> sent = new ArrayList<>();

            @Override
            public void send(RelayFrame frame) {
                sent.add(frame);
                // Simulate peer dropping connection during/immediately after the first frame
                registry.onDisconnected(bob, controllerRef.get());
            }

            @Override
            public void close() {}
        };
        controllerRef.set(disconnectingController);

        registry.onConnected(bob, disconnectingController);

        // Only the first message was delivered before disconnect stopped the drain;
        // remaining messages stay safely in the spool instead of being dropped.
        List<SpooledMessage> remaining = fakeSpool.fetchForTarget(bob.toString()).join();
        assertThat(remaining).hasSize(2);
        assertThat(remaining.get(0).payload()).isEqualTo("msg2".getBytes());
        assertThat(remaining.get(1).payload()).isEqualTo("msg3".getBytes());

        // Upon reconnecting, the remaining messages are delivered
        RecordingController freshBobController = new RecordingController(new AtomicReference<>());
        registry.onConnected(bob, freshBobController);

        List<RelayFrame> flushed = freshBobController.allSentFrames();
        assertThat(flushed).hasSize(2);
        assertThat(flushed.get(0).payload()).isEqualTo("msg2".getBytes());
        assertThat(flushed.get(1).payload()).isEqualTo("msg3".getBytes());
        assertThat(fakeSpool.fetchForTarget(bob.toString()).join()).isEmpty();
    }

    @Test
    void ignoreNonForwardFrameTypesDefensively() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");

        AtomicReference<RelayFrame> deliveredFrame = new AtomicReference<>();
        RelayController bobController = new RecordingController(deliveredFrame);
        registry.onConnected(bob, bobController);

        // Non-forward (DELIVER-type) frame arriving at the server should be defensively ignored
        RelayFrame deliverFrame = new RelayFrame(false, bob.toString(), "Payload".getBytes());
        registry.onFrame(alice, deliverFrame);

        assertThat(deliveredFrame.get()).isNull();
    }

    // --- A-1 (pre-m6h-hardening-plan.md, Track A): onDisconnected now actually deregisters. ---

    @Test
    void disconnectRemovesPeerSoLaterForwardsAreSpooledNotStale() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");

        RelayController bobController = new RecordingController(new AtomicReference<>());
        registry.onConnected(bob, bobController);

        registry.onDisconnected(bob, bobController);

        // Should behave exactly like frameForOfflineTargetIsSpooledNotDropped -- not throw, spooled.
        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "Test".getBytes()));

        assertThat(fakeSpool.fetchForTarget(bob.toString()).join()).hasSize(1);
    }

    @Test
    void staleDisconnectSignalDoesNotEvictAFresherReconnectedController() {
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");

        RelayController oldController = new RecordingController(new AtomicReference<>());
        AtomicReference<RelayFrame> deliveredFrame = new AtomicReference<>();
        RelayController newController = new RecordingController(deliveredFrame);

        registry.onConnected(bob, oldController);
        registry.onConnected(bob, newController); // Bob reconnected on a fresh controller

        // A late onDisconnected for the OLD connection arrives after the reconnect already
        // happened -- must not remove Bob's current (new) entry.
        registry.onDisconnected(bob, oldController);

        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "still routable".getBytes()));

        assertThat(deliveredFrame.get()).isNotNull();
    }

    /** A small, explicit RelayController fake — {@code close()} means this can no longer be a lambda. */
    private static final class RecordingController implements RelayController {
        private final AtomicReference<RelayFrame> sink;
        private final List<RelayFrame> allFrames = new CopyOnWriteArrayList<>();

        RecordingController(AtomicReference<RelayFrame> sink) {
            this.sink = sink;
        }

        @Override
        public void send(RelayFrame frame) {
            sink.set(frame);
            allFrames.add(frame);
        }

        @Override
        public void close() {
            // not exercised by RelayRegistry's own logic -- it never calls close() itself
        }

        List<RelayFrame> allSentFrames() {
            return allFrames;
        }
    }

    /**
     * An in-memory {@link RelaySpool} double — exactly the reason {@link RelaySpool} is an
     * interface at all, per its own Javadoc: this test needs to exercise enqueue-on-no-route and
     * drain-on-reconnect without a real SQLite file for every test. Every future completes
     * synchronously via {@link CompletableFuture#completedFuture}, deliberately — this class's
     * own concurrency/thread-confinement story is {@code SqliteRelaySpool}'s own, already covered
     * thoroughly in {@code SqliteRelaySpoolTest}; re-testing it here through a fake that doesn't
     * even have a background thread would prove nothing.
     */
    private static final class FakeRelaySpool implements RelaySpool {
        private final Map<String, List<SpooledMessage>> byTarget = new HashMap<>();
        private long nextId = 1;
        private EnqueueResult forcedResult = EnqueueResult.ACCEPTED;

        void forceNextEnqueueResult(EnqueueResult result) {
            this.forcedResult = result;
        }

        @Override
        public CompletableFuture<EnqueueResult> enqueue(String targetPeerId, String senderPeerId, byte[] payload) {
            if (forcedResult != EnqueueResult.ACCEPTED) {
                return CompletableFuture.completedFuture(forcedResult);
            }
            byTarget.computeIfAbsent(targetPeerId, key -> new ArrayList<>())
                    .add(new SpooledMessage(nextId++, senderPeerId, payload, Instant.now()));
            return CompletableFuture.completedFuture(EnqueueResult.ACCEPTED);
        }

        @Override
        public CompletableFuture<List<SpooledMessage>> fetchForTarget(String targetPeerId) {
            return CompletableFuture.completedFuture(List.copyOf(byTarget.getOrDefault(targetPeerId, List.of())));
        }

        @Override
        public CompletableFuture<Void> delete(long id) {
            byTarget.values().forEach(messages -> messages.removeIf(message -> message.id() == id));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Integer> purgeExpired(Instant now) {
            return CompletableFuture.completedFuture(0); // not exercised by RelayRegistry's own tests
        }
    }
}
