package com.p2pchat.network;

/** Returned once a Relay protocol stream is active. Lets either side send a RelayFrame to the other. */
public interface RelayController {
    void send(RelayFrame frame);
}
