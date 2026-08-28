package com.p2pchat.daemon.contact;

import com.p2pchat.storage.model.Contact;

/**
 * What {@link ContactService#addContact} resolves to — always, never a failed future. Matches
 * {@code SessionManager.sendChatMessage}'s own established convention (see that method's
 * Javadoc: "a future that always resolves ... never completes exceptionally") for the same
 * reason it applies there: a malformed invite code, a peer who hasn't published a discovery
 * record right now, or a record that fails verification are ordinary, expected outcomes of
 * trying to add a contact over a real network — not programming bugs — so a typed result a
 * caller is expected to switch on fits better than an exception a caller has to remember to
 * catch. Sealed, matching {@code ChatWireMessage}/{@code FileTransferMessage}'s own established
 * "closed set of outcomes" convention rather than an open exception hierarchy.
 */
public sealed interface ContactAddResult {

    record Added(Contact contact) implements ContactAddResult {
    }

    record Failed(Reason reason, String message) implements ContactAddResult {
    }

    /**
     * Deliberately not unified with a {@code DaemonErrorCode} enum here — that type doesn't
     * exist yet (M6g-4 scope). The eventual JSON-RPC layer is expected to map each of these onto
     * whichever {@code DaemonErrorCode} fits (roughly: MALFORMED_INVITE_CODE/VERIFICATION_FAILED
     * → MALFORMED_RECORD, NO_DISCOVERY_SERVER/LOOKUP_FAILED/PEER_NOT_FOUND → PEER_UNREACHABLE or
     * RELAY_UNAVAILABLE depending on which failed) — noted here so that mapping isn't invented
     * from scratch later, not built ahead of the milestone that actually needs it.
     */
    enum Reason {
        /** {@code InviteCodeCodec.decode} rejected the invite code itself. */
        MALFORMED_INVITE_CODE,
        /** The invite code had no discovery address, and this daemon has no default configured. */
        NO_DISCOVERY_SERVER,
        /** The discovery lookup itself failed (network error, timeout) rather than returning a definitive answer. */
        LOOKUP_FAILED,
        /** The discovery lookup completed and definitively found nothing for this peer ID. */
        PEER_NOT_FOUND,
        /** A record was found, but {@code DiscoveryRecordCodec.verifyAndDecode} rejected it — see the message for which reason. */
        VERIFICATION_FAILED,
        /**
         * Something unexpected failed after a successful, verified lookup — e.g. a storage
         * error persisting the contact. Kept distinct from {@code LOOKUP_FAILED} deliberately:
         * collapsing this into that label (as {@code SessionManager.sendChatMessage} accepts
         * doing for its own single {@code ConnectivityStatus.UNREACHABLE} — see that method's
         * own comment) would actively mislead whoever reads it toward debugging the wrong
         * subsystem. Cheap to keep separate, so kept separate.
         */
        INTERNAL_ERROR
    }
}
