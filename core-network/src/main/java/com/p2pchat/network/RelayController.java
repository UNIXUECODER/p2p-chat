package com.p2pchat.network;

/**
 * Returned once a Relay protocol stream is active. Lets either side send a RelayFrame to the other.
 *
 * <p><b>A-1 addition ({@code close()}):</b> pre-m6h-hardening-plan.md's Track A finding A-2 named
 * a real leak here — {@code ConnectionStrategy} used to dial a brand-new relay connection per
 * send and never close the old one. {@link RelaySession} is what actually needs this: on
 * reconnect, it must release the stale controller before dialing a fresh one.
 *
 * <p>This is no longer a lambda-compatible (single-abstract-method) interface now that there are
 * two methods — the couple of test doubles that used to construct one as {@code frame -> ...}
 * needed updating to an explicit implementation. That's the one real piece of fallout from this
 * change, and it's confined to test code.
 */
public interface RelayController {
    void send(RelayFrame frame);

    /**
     * Closes the underlying relay stream. Safe to call more than once, and safe to call on a
     * controller whose connection already dropped on its own.
     */
    void close();
}
