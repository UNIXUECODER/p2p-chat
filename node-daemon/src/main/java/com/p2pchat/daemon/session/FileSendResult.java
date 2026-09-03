package com.p2pchat.daemon.session;

import com.p2pchat.network.ConnectivityStatus;

/**
 * M6g-4: what {@link SessionManager#sendFile} resolves to. Added specifically because building
 * the {@code files.send} JSON-RPC method (§7: returns {@code { transferId }}) against {@code
 * sendFile}'s prior return type — bare {@link ConnectivityStatus}, mirroring {@link
 * SessionManager#sendChatMessage}'s own shape — surfaced a real gap: {@code sendFile} generates
 * the transfer's {@code transferId} internally (a fresh {@code UUID}, passed to {@link
 * FileTransferHandler#registerOutgoingTransfer} for its own bookkeeping) but never handed it back
 * to the caller. There was no way for a JSON-RPC caller to learn which transfer their own offer
 * became — and no way to fake one either: {@code FileTransferHandler} already tracks outgoing
 * transfers keyed by this exact id, so a router-generated id would silently mismatch the real one
 * and corrupt every later {@code files.accept}/progress correlation for that transfer, not just
 * be a cosmetic omission.
 *
 * <p>Sibling top-level record, not nested inside {@link SessionManager} — matching {@code
 * DiscoveryLookupResult}'s own precedent for "a simple, single-case return-value tuple, not a
 * closed set of outcomes" (contrast {@code ContactAddResult}, a genuinely sealed hierarchy, which
 * this is not).
 *
 * @param transferId the id this transfer was registered under, or {@code null} if no transfer was
 *                    ever actually registered — the file didn't exist, or offer construction
 *                    failed before a transfer id was even generated. Matches {@code
 *                    sendChatMessage}'s own established shape of "always resolves, a failure just
 *                    means less of the result is populated" rather than a separate failure type.
 * @param status      identical meaning to {@link SessionManager#sendChatMessage}'s own return
 *                     value — never null.
 */
public record FileSendResult(String transferId, ConnectivityStatus status) {
}
