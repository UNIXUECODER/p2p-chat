package com.p2pchat.network;

/** Returned once an Envelope stream is active. Lets either side send raw bytes to the other. */
public interface EnvelopeController {
    void send(byte[] data);
}
