package com.p2pchat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceIdTest {

    @Test
    void defaultDeviceIdValue() {
        assertThat(DeviceId.DEFAULT.value()).isEqualTo("0");
        assertThat(DeviceId.DEFAULT.toString()).isEqualTo("0");
    }

    @Test
    void customDeviceIdCreationAndEquality() {
        DeviceId dev1 = new DeviceId("device-123");
        DeviceId dev2 = new DeviceId("device-123");

        assertThat(dev1).isEqualTo(dev2);
        assertThat(dev1.value()).isEqualTo("device-123");
    }

    @Test
    void rejectsNullOrBlankValue() {
        assertThatThrownBy(() -> new DeviceId(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DeviceId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
