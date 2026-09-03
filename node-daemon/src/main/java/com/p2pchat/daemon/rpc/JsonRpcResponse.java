package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonCodec;
import com.p2pchat.daemon.json.JsonNull;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonValue;

/**
 * M6g-4: a JSON-RPC 2.0 response — either {@code { jsonrpc, id, result }} or {@code { jsonrpc,
 * id, error }}, per the spec's §5, never both. Sealed with two cases, matching this project's
 * established "closed set of outcomes" convention ({@code ContactAddResult}, {@code
 * DispatchedMessage}, {@code ChatWireMessage}) rather than one record with a nullable {@code
 * error} field a caller could accidentally populate alongside a {@code result}.
 *
 * <p><b>{@link #toJsonValue} is the primary form, {@link #toJsonText} a convenience built on
 * it</b> — a single request's response only ever needs the text form ({@link
 * JsonRpcRouter#onMessage} sends it straight to a {@code WebSocketSession}), but a batch
 * response ({@code JsonRpcRouter}'s own batch handling) needs to embed several individual
 * responses as elements of one outer {@code JsonArray}, which requires each one as a {@link
 * JsonValue} tree, not a pre-serialized string a batch handler would otherwise have to
 * re-parse just to nest it — discovered by actually building that path, not anticipated here in
 * advance.
 */
public sealed interface JsonRpcResponse {

    JsonValue id();

    /** The {@code JsonValue} tree for this response — see interface Javadoc for why this, not {@link #toJsonText}, is primary. */
    JsonObject toJsonValue();

    /** Convenience: {@code JsonCodec.write(toJsonValue())}. */
    default String toJsonText() {
        return JsonCodec.write(toJsonValue());
    }

    record Success(JsonValue id, JsonValue result) implements JsonRpcResponse {
        @Override
        public JsonObject toJsonValue() {
            return JsonObject.builder()
                    .put("jsonrpc", "2.0")
                    .put("id", id == null ? JsonNull.INSTANCE : id)
                    .put("result", result)
                    .build();
        }
    }

    record Error(JsonValue id, JsonRpcError error) implements JsonRpcResponse {
        @Override
        public JsonObject toJsonValue() {
            return JsonObject.builder()
                    .put("jsonrpc", "2.0")
                    // JSON-RPC 2.0 §5: "If there was an error in detecting the id in the Request
                    // object (e.g. Parse error/Invalid Request), it MUST be Null" -- this
                    // project's own id-recovery logic (JsonRpcRequestException.bestEffortId)
                    // already produces exactly that null in the undeterminable case, so this
                    // constructor doesn't need its own separate fallback here; a genuine
                    // JsonRpcRequest's id is never itself a Java null except via that same
                    // recovery path -- an id present as JSON null is JsonNull.INSTANCE instead,
                    // not a Java null (see JsonRpcRequest's own Javadoc for that distinction).
                    .put("id", id == null ? JsonNull.INSTANCE : id)
                    .put("error", error.toJson())
                    .build();
        }
    }
}
