package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonValue;

/**
 * Thrown by {@link JsonRpcRequest#parse} when the JSON-RPC 2.0 envelope itself is malformed —
 * missing/wrong {@code jsonrpc}, missing/wrong-typed {@code method}, wrong-typed {@code id}, or
 * {@code params} present but not an object. Checked, matching this project's established
 * "untrusted external input decode failure" convention ({@code InviteCodeException}, {@code
 * DiscoveryRecordException}) — a request arriving over a WebSocket from a UI (or an attacker) is
 * exactly that kind of input.
 *
 * <p>Deliberately distinct from {@link JsonRpcParamException} — this fires before a {@link
 * JsonRpcRequest} exists at all, so there is no {@code request.id()} a catch site could read;
 * {@link #bestEffortId} carries whatever id this class could still determine despite the
 * envelope being otherwise broken, per JSON-RPC 2.0 §5's own instruction to echo a request's id
 * back on error whenever it's determinable — {@code null} only when it genuinely isn't (e.g. the
 * top-level value isn't even an object, or {@code id} itself is present but the wrong type).
 * {@link JsonRpcParamException}, by contrast, only ever fires after a {@link JsonRpcRequest}
 * already exists with a known-good id, so it carries no id of its own — the catch site already
 * has one.
 */
public final class JsonRpcRequestException extends Exception {

    private final JsonValue bestEffortId;

    public JsonRpcRequestException(String message, JsonValue bestEffortId) {
        super(message);
        this.bestEffortId = bestEffortId;
    }

    /** The request's {@code id}, if this class could still determine one — {@code null} otherwise (see class Javadoc). */
    public JsonValue bestEffortId() {
        return bestEffortId;
    }
}
