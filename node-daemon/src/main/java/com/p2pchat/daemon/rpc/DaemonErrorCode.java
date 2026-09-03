package com.p2pchat.daemon.rpc;

/**
 * M6g-4: the application-level error vocabulary {@code docs/M6g-gap-analysis-and-plan.md §3}
 * names exactly — ten values, no more, no fewer than the plan lists. Each maps to a real JSON-RPC
 * 2.0 numeric error code, per the plan's own instruction ("Mapped to JSON-RPC 2.0 error codes —
 * {@code -32600} range for standard, {@code -32000} range for application-specific").
 *
 * <p><b>The JSON-RPC 2.0 spec reserves {@code -32768} to {@code -32000}</b> for predefined
 * errors, carving out {@code -32000} to {@code -32099} specifically as "Reserved for
 * implementation-defined server-errors" — exactly the range this project's own application
 * errors belong in, distinct from the protocol-level codes ({@code -32700} Parse error, {@code
 * -32600} Invalid Request, {@code -32601} Method not found, {@code -32602} Invalid params, {@code
 * -32603} Internal error) that a request never even reaches application dispatch to produce. This
 * enum only carries the two standard codes this project's own error vocabulary actually names —
 * {@link #INVALID_REQUEST}, {@link #METHOD_NOT_FOUND} — because {@code docs/M6g-gap-analysis-
 * and-plan.md §3} lists both by name alongside the eight application-specific ones, treating them
 * as one flat vocabulary. The other three standard codes ({@code PARSE_ERROR}, {@code
 * INVALID_PARAMS}, {@code INTERNAL_ERROR}) are NOT named in that vocabulary at all — {@link
 * JsonRpcError} constructs those directly as raw codes for the transport-level failures they
 * describe (a request that never parsed, a specific field within an otherwise-valid method call
 * that's missing or malformed, a genuinely unexpected exception), rather than stretching this
 * enum to cover cases the plan never assigned it. See {@link JsonRpcError}'s own Javadoc.
 *
 * <p><b>Where each value is actually used</b> — traced against real call sites, not guessed at in
 * advance, matching this project's own established practice of naming a gap only once a real
 * caller needs it:
 * <ul>
 *   <li>{@link #INVALID_REQUEST} — the JSON-RPC envelope itself is malformed (missing/wrong
 *   {@code jsonrpc}, missing/wrong-typed {@code method}), or a request is well-formed but makes
 *   no sense given current daemon state (e.g. {@code identity.create} when an identity already
 *   exists — see {@link JsonRpcRouter#handleIdentityCreate} for why that's treated as a request
 *   problem, not silently allowed to overwrite the existing identity).</li>
 *   <li>{@link #METHOD_NOT_FOUND} — an unrecognized method name, or a recognized-but-deliberately-
 *   unimplemented one ({@code conversations.createGroup} → M8, {@code files.cancel} → M7), per
 *   the plan's own method-mapping table.</li>
 *   <li>{@link #PEER_UNREACHABLE} — a specific peer couldn't be reached: a discovery lookup that
 *   definitively found nothing for that peer ID, or an outbound send that resolved to {@code
 *   ConnectivityStatus.UNREACHABLE}.</li>
 *   <li>{@link #RELAY_UNAVAILABLE} — the discovery/relay infrastructure itself, not a specific
 *   peer, is the problem: no discovery server configured, or a discovery lookup that failed
 *   (network error, timeout) rather than returning a definitive found/not-found answer.</li>
 *   <li>{@link #MALFORMED_RECORD} — an invite code that didn't decode, or a discovery record
 *   whose signature verification failed.</li>
 *   <li>{@link #CRYPTO_FAILURE} — reserved for a Signal Protocol operation failing (session
 *   establishment, encrypt/decrypt) in a way {@code sendChatMessage}/{@code sendFile}'s own
 *   "never completes exceptionally" contract already collapses into {@code
 *   ConnectivityStatus.UNREACHABLE} before this router ever sees it — named here because the
 *   plan names it, not because a current call site can actually produce it distinctly from
 *   {@link #PEER_UNREACHABLE} yet. See the README's M6g-4 section for this as a tracked, honest
 *   gap rather than a silently-unused enum value.</li>
 *   <li>{@link #DUPLICATE_MESSAGE} — reserved the same way: {@code SessionManager}'s own dedup
 *   (message-id check before persistence) is an inbound-path concern with no RPC-method call site
 *   today. Named for the same reason as {@link #CRYPTO_FAILURE}.</li>
 *   <li>{@link #STORAGE_FAILURE} — an unexpected exception from a {@code StorageService} call
 *   (e.g. {@code contacts.add} succeeding at discovery/verification but failing to persist).</li>
 *   <li>{@link #UNKNOWN_CONVERSATION} — {@code messages.send}/{@code messages.history}/{@code
 *   files.send} given a {@code conversationId} that doesn't resolve to a known peer.</li>
 *   <li>{@link #UNKNOWN_CONTACT} — reserved for a future method keyed by contact/peer id that
 *   needs to distinguish "no such contact" from "no such conversation" — no current method needs
 *   this distinction ({@code contacts.add} treats an unknown peer as {@link #PEER_UNREACHABLE},
 *   a discovery-layer fact, not a storage-layer one). Named because the plan names it.</li>
 * </ul>
 */
public enum DaemonErrorCode {
    PEER_UNREACHABLE(-32000),
    RELAY_UNAVAILABLE(-32001),
    MALFORMED_RECORD(-32002),
    CRYPTO_FAILURE(-32003),
    DUPLICATE_MESSAGE(-32004),
    STORAGE_FAILURE(-32005),
    UNKNOWN_CONVERSATION(-32006),
    UNKNOWN_CONTACT(-32007),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601);

    private final int rpcCode;

    DaemonErrorCode(int rpcCode) {
        this.rpcCode = rpcCode;
    }

    /** The numeric JSON-RPC 2.0 {@code error.code} this value serializes to. */
    public int rpcCode() {
        return rpcCode;
    }
}
