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

        RelayController bobController = deliveredFrame::set;

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
        RelayController bobController = deliveredFrame::set;
        registry.onConnected(bob, bobController);

        // Non-forward (DELIVER-type) frame arriving at the server should be defensively ignored
        RelayFrame deliverFrame = new RelayFrame(false, bob.toString(), "Payload".getBytes());
        registry.onFrame(alice, deliverFrame);

        assertThat(deliveredFrame.get()).isNull();
    }
}
