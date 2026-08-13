package com.p2pchat.model;

import java.util.Objects;

/**
 * A device identifier, for the multi-device support docs/architecture-spec.md §13 already
 * reserves room for. Always {@link #DEFAULT} ("0", a single device per identity) until that
 * milestone exists.
 *
 * <p>Kept as its own type — not a bare String — for the same reason PeerId is: §4 and §13
 * already describe every message and session as carrying a device_id field ("always \"0\" for
 * now... the wire format doesn't change" when multi-device lands). Introducing the type now,
 * while it only ever holds one value, means nothing about the wire format or the storage schema
 * needs to change shape later — only where the value comes from.
 */
public record DeviceId(String value) {

    /** The only value that exists in v1 — every message and session uses this until multi-device linking lands. */
    public static final DeviceId DEFAULT = new DeviceId("0");

    public DeviceId {
        Objects.requireNonNull(value, "DeviceId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("DeviceId value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
