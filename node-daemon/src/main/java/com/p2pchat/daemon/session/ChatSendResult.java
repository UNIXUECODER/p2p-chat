package com.p2pchat.daemon.session;

import com.p2pchat.network.ConnectivityStatus;

/**
 * M6g-4: what {@link SessionManager#sendChatMessage} resolves to. The exact same gap, found the
 * exact same way, as {@link FileSendResult} — see that record's own Javadoc for the full
 * reasoning, which applies here without modification: building the {@code messages.send}
 * JSON-RPC method (§7: returns {@code { messageId }}) against {@code sendChatMessage}'s prior
 * return type — bare {@link ConnectivityStatus} — found that it generates the message's {@code
 * messageId} internally (a fresh {@code UUID}, already persisted via {@code
 * StorageService.saveMessage} before the send is even attempted) but never handed it back to the
 * caller, with the identical consequence: no way for an RPC caller to learn which message their
 * own send became, and no safe way to fake one (a router-generated id would not match the row
 * {@code sendChatMessage} already persisted under its own real id).
 *
 * <p>Sibling top-level record, matching {@link FileSendResult}'s own precedent (itself matching
 * {@code DiscoveryLookupResult}'s) — not nested inside {@link SessionManager}.
 *
 * @param messageId the id this message was generated under — always present, never {@code null},
 *                   since {@code sendChatMessage} generates {@code messageId} before its {@code
 *                   try} block even begins (confirmed by reading the real method body, not
 *                   assumed by analogy to {@link FileSendResult#transferId}, whose {@code null}
 *                   case doesn't apply here for exactly that reason). <b>Not a guarantee the
 *                   message was actually persisted</b>, though — an exception thrown before
 *                   {@code storage.runInTransaction(...)} runs (e.g. session establishment or
 *                   encryption failing) still reaches the {@code catch} block with a real,
 *                   already-generated {@code messageId} in scope, resolving to {@code
 *                   UNREACHABLE} with that id reported despite no row ever having been written
 *                   for it — genuinely different from the ordinary {@code UNREACHABLE} case
 *                   (outbound send failing after persistence, leaving a real {@code FAILED} row
 *                   {@code messages.history} would show). A caller cannot currently tell these two
 *                   apart from this result alone — named here as a real, narrow distinction this
 *                   record doesn't yet surface, not silently smoothed over.
 * @param status     identical meaning to {@link SessionManager#sendFile}'s own {@link
 *                   FileSendResult#status}.
 */
public record ChatSendResult(String messageId, ConnectivityStatus status) {
}
