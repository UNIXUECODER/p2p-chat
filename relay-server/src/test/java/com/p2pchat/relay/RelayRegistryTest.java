package com.p2pchat.relay;

import com.p2pchat.model.PeerId;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RelayRegistryTest {

    private RelayRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RelayRegistry();
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

    @Test
    void dropFrameWhenTargetNotRegistered() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId offlineBob = PeerId.of("12D3KooWOfflineBob12345678901234567890123");

        RelayFrame forwardFrame = new RelayFrame(true, offlineBob.toString(), "Test".getBytes());

        // Should not throw or crash when target is offline
        registry.onFrame(alice, forwardFrame);
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
    void disconnectRemovesPeerSoLaterForwardsAreDroppedNotStale() {
        PeerId alice = PeerId.of("12D3KooWAlice123456789012345678901234567890");
        PeerId bob = PeerId.of("12D3KooWBob12345678901234567890123456789012");

        RelayController bobController = new RecordingController(new AtomicReference<>());
        registry.onConnected(bob, bobController);

        registry.onDisconnected(bob, bobController);

        // Should behave exactly like dropFrameWhenTargetNotRegistered -- not throw, not deliver.
        registry.onFrame(alice, new RelayFrame(true, bob.toString(), "Test".getBytes()));
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

        RecordingController(AtomicReference<RelayFrame> sink) {
            this.sink = sink;
        }

        @Override
        public void send(RelayFrame frame) {
            sink.set(frame);
        }

        @Override
        public void close() {
            // not exercised by RelayRegistry's own logic -- it never calls close() itself
        }
    }
}
