package com.p2pchat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerIdTest {

    @Test
    void validPeerIdCreationAndEquality() {
        PeerId peer1 = PeerId.of("12D3KooWAlice1234567890");
        PeerId peer2 = new PeerId("12D3KooWAlice1234567890");

        assertThat(peer1).isEqualTo(peer2);
        assertThat(peer1.toString()).isEqualTo("12D3KooWAlice1234567890");
        assertThat(peer1.value()).isEqualTo("12D3KooWAlice1234567890");
    }

    @Test
    void rejectsNullOrBlankValue() {
        assertThatThrownBy(() -> PeerId.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> PeerId.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> PeerId.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
