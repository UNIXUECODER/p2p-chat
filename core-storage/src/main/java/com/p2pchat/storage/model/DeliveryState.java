package com.p2pchat.storage.model;

/** Delivery state of a locally-sent or locally-received message. Matches messages.delivery_state's CHECK constraint. */
public enum DeliveryState {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
