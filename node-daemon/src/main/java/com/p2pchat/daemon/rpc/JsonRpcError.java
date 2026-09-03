package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonNull;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonValue;

/**
 * M6g-4: the JSON-RPC 2.0 {@code error} object — {@code { code, message, data? }}, per the spec's
 * §5.1. {@code data} is optional per spec and modeled as such here ({@code null} means "omit the
 * field entirely," matching {@link JsonRpcResponse.Error#toJson}'s handling — not "include the
 * field with a JSON {@code null} value," which would be a different, spec-permitted but needless
 * wire shape for the common case).
 *
 * <p><b>Two families of factory, deliberately kept distinct rather than unified behind one.</b>
 * {@link #of(DaemonErrorCode, String)} covers this project's own named application vocabulary —
 * see {@link DaemonErrorCode}'s own Javadoc for why that enum is intentionally limited to what
 * {@code docs/M6g-gap-analysis-and-plan.md §3} actually names. {@link #parseError()}, {@link
 * #invalidRequest}, {@link #methodNotFound}, {@link #invalidParams}, and {@link #internalError}
 * cover the three standard JSON-RPC 2.0 codes the plan's vocabulary does NOT name ({@code
 * -32700}, {@code -32602}, {@code -32603}) plus the two it does ({@code -32600}, {@code -32601},
 * also reachable via {@link DaemonErrorCode#INVALID_REQUEST}/{@link
 * DaemonErrorCode#METHOD_NOT_FOUND} — both paths produce an identical wire result; {@link
 * JsonRpcRouter} uses whichever reads more clearly at each call site). {@link #invalidParams} in
 * particular exists because {@link DaemonErrorCode} has no dedicated value for "this method
 * exists and the envelope is fine, but a specific required field is missing or the wrong type" —
 * collapsing that into {@link DaemonErrorCode#INVALID_REQUEST} would lose a real, useful
 * distinction a client can act on differently (a malformed request the client can fix by reading
 * §7 more carefully, versus a malformed envelope suggesting a client-library bug) for no reason
 * beyond the enum not happening to name it.
 */
public record JsonRpcError(int code, String message, JsonValue data) {

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    public static JsonRpcError of(DaemonErrorCode errorCode, String message) {
        return new JsonRpcError(errorCode.rpcCode(), message);
    }

    public static JsonRpcError parseError() {
        return new JsonRpcError(-32700, "Parse error: request body is not valid JSON");
    }

    public static JsonRpcError invalidRequest(String detail) {
        return new JsonRpcError(-32600, "Invalid Request: " + detail);
    }

    public static JsonRpcError methodNotFound(String method) {
        return new JsonRpcError(-32601, "Method not found: " + method);
    }

    public static JsonRpcError invalidParams(String detail) {
        return new JsonRpcError(-32602, "Invalid params: " + detail);
    }

    public static JsonRpcError internalError(String detail) {
        return new JsonRpcError(-32603, "Internal error: " + detail);
    }

    JsonObject toJson() {
        JsonObject.Builder builder = JsonObject.builder()
                .put("code", (long) code)
                .put("message", message);
        if (data != null && !(data instanceof JsonNull)) {
            builder.put("data", data);
        }
        return builder.build();
    }
}
