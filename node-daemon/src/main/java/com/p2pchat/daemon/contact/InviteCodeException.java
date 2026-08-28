package com.p2pchat.daemon.contact;

/**
 * Thrown by {@link InviteCodeCodec#decode} when a string a user pasted or scanned doesn't
 * resolve to a usable {@link InviteCode} — checked, matching {@code
 * core-discovery.DiscoveryRecordException}'s precedent for decoding untrusted external input:
 * a caller handling one kind of untrusted decode failure in this project is generally handling
 * the other too (see {@code ContactService.addContact}), and the checked/unchecked split should
 * track "is this untrusted input" rather than differ arbitrarily between two decoders solving
 * the same kind of problem.
 */
public final class InviteCodeException extends Exception {

    public InviteCodeException(String message) {
        super(message);
    }

    public InviteCodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
