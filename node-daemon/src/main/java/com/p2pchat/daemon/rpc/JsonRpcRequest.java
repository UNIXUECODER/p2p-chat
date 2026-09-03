package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonNumber;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonString;
import com.p2pchat.daemon.json.JsonValue;

/**
 * M6g-4: a parsed, validated JSON-RPC 2.0 request — {@code { jsonrpc: "2.0", id?, method,
 * params? }}, per the spec's §4. Built on M6c's {@link com.p2pchat.daemon.json.JsonCodec}/{@link
 * JsonValue} tree rather than a seventh hand-rolled format, matching {@code InviteCodeCodec}'s
 * own precedent for exactly this reason.
 *
 * <p><b>{@code id} is a real Java {@code null} when the request is a notification</b> (the
 * {@code id} member absent entirely) — deliberately distinct from {@link
 * com.p2pchat.daemon.json.JsonNull#INSTANCE}, which means the member was present with a JSON
 * {@code null} value. JSON-RPC 2.0 §4.1 draws exactly this distinction: "If [the id member] is
 * not included it is assumed to be a notification" (no response ever sent) versus an id present
 * as {@code null} (still a real request, expecting a response that echoes {@code id: null} back)
 * — a real, if unusual, protocol case some implementations conflate but this project's own
 * "deliberately strict, per spec" convention ({@code JsonCodec}'s own Javadoc) argues for
 * honoring precisely rather than collapsing.
 *
 * <p><b>{@code params} is always non-null</b> — an absent or {@code null} {@code params} member
 * normalizes to an empty {@link JsonObject}, so every method handler in {@link JsonRpcRouter} can
 * call {@link #requireString}/{@link #optionalString} unconditionally rather than null-checking
 * {@code params} itself first.
 *
 * <p><b>Only object-form ({@code {"field": value}}) params are supported</b> — JSON-RPC 2.0
 * permits positional (array) params too, but nothing in this project's actual method surface uses
 * them (every {@code architecture-spec.md §7} example is named-field), so array-form params is
 * treated as a request-shape problem (see {@link #parse}) rather than a case every handler would
 * need to accept without ever actually using.
 */
public record JsonRpcRequest(JsonValue id, String method, JsonObject params) {

    /** True if this request has no {@code id} member at all — a notification; no response is ever sent for one. */
    public boolean isNotification() {
        return id == null;
    }

    /**
     * Parses and validates a single JSON-RPC 2.0 request object from an already-parsed {@link
     * JsonValue} (the caller is expected to have called {@code JsonCodec.parse(text)} first — see
     * {@link JsonRpcRouter#onMessage}, which separates "not valid JSON at all" (-32700, this
     * class never sees it) from "valid JSON but not a valid request" (-32600, this method's job).
     *
     * @throws JsonRpcRequestException if the envelope itself is malformed — see that class's own
     *                                 Javadoc for exactly what counts and how {@code id} is best-
     *                                 effort recovered for the resulting error response even so.
     */
    public static JsonRpcRequest parse(JsonValue raw) throws JsonRpcRequestException {
        JsonObject object;
        try {
            object = raw.asObject();
        } catch (IllegalStateException e) {
            throw new JsonRpcRequestException("a request must be a JSON object", null);
        }

        // Best-effort id extraction FIRST, before validating anything else below -- so a request
        // that's otherwise malformed but has a perfectly usable id can still get that id echoed
        // back on its error response, per JSON-RPC 2.0 §5's "MUST reply with the same value...
        // if included." Only a genuinely undeterminable id (absent, or present with a type that
        // isn't string/number/null) falls back to null, matching the spec's own instruction for
        // exactly that case.
        JsonValue bestEffortId = extractBestEffortId(object);

        if (!object.has("jsonrpc") || !(object.get("jsonrpc") instanceof JsonString version) || !version.value().equals("2.0")) {
            throw new JsonRpcRequestException("\"jsonrpc\" must be exactly \"2.0\"", bestEffortId);
        }

        if (!object.has("method")) {
            throw new JsonRpcRequestException("\"method\" is required", bestEffortId);
        }
        String method;
        try {
            method = object.get("method").asString();
        } catch (IllegalStateException e) {
            throw new JsonRpcRequestException("\"method\" must be a string", bestEffortId);
        }

        JsonObject params;
        if (!object.has("params") || object.get("params").isNull()) {
            params = JsonObject.of();
        } else {
            try {
                params = object.get("params").asObject();
            } catch (IllegalStateException e) {
                // See class Javadoc -- positional (array) params is spec-legal but genuinely
                // unsupported here, not silently ignored or half-handled.
                throw new JsonRpcRequestException(
                        "\"params\" must be an object (positional/array params are not supported)", bestEffortId);
            }
        }

        JsonValue id = object.has("id") ? object.get("id") : null;
        if (id != null && !id.isNull() && !(id instanceof JsonString) && !(id instanceof JsonNumber)) {
            throw new JsonRpcRequestException("\"id\" must be a string, number, or null", bestEffortId);
        }

        return new JsonRpcRequest(id, method, params);
    }

    private static JsonValue extractBestEffortId(JsonObject object) {
        if (!object.has("id")) {
            return null;
        }
        JsonValue candidate = object.get("id");
        if (candidate.isNull() || candidate instanceof JsonString || candidate instanceof JsonNumber) {
            return candidate;
        }
        return null; // present but the wrong type -- not usable, spec says fall back to null
    }

    // ---------------------------------------------------------------- params field accessors

    /** @throws JsonRpcParamException if {@code field} is missing, {@code null}, or not a string. */
    public String requireString(String field) throws JsonRpcParamException {
        if (!params.has(field) || params.get(field).isNull()) {
            throw new JsonRpcParamException("\"" + field + "\" is required");
        }
        try {
            return params.get(field).asString();
        } catch (IllegalStateException e) {
            throw new JsonRpcParamException("\"" + field + "\" must be a string");
        }
    }

    /**
     * @return {@code defaultValue} if {@code field} is missing or {@code null}
     * @throws JsonRpcParamException if {@code field} is present but not a string — see class
     *                                Javadoc's linked reasoning on {@link JsonRpcParamException}
     *                                for why a wrong-typed optional field still fails loud.
     */
    public String optionalString(String field, String defaultValue) throws JsonRpcParamException {
        if (!params.has(field) || params.get(field).isNull()) {
            return defaultValue;
        }
        try {
            return params.get(field).asString();
        } catch (IllegalStateException e) {
            throw new JsonRpcParamException("\"" + field + "\" must be a string");
        }
    }

    /** @throws JsonRpcParamException if {@code field} is missing, {@code null}, or not a number. */
    public int requireInt(String field) throws JsonRpcParamException {
        if (!params.has(field) || params.get(field).isNull()) {
            throw new JsonRpcParamException("\"" + field + "\" is required");
        }
        try {
            return params.get(field).asInt();
        } catch (IllegalStateException | NumberFormatException e) {
            throw new JsonRpcParamException("\"" + field + "\" must be an integer");
        }
    }

    /** @return {@code defaultValue} if {@code field} is missing or {@code null}; throws if present but not a number. */
    public int optionalInt(String field, int defaultValue) throws JsonRpcParamException {
        if (!params.has(field) || params.get(field).isNull()) {
            return defaultValue;
        }
        try {
            return params.get(field).asInt();
        } catch (IllegalStateException | NumberFormatException e) {
            throw new JsonRpcParamException("\"" + field + "\" must be an integer");
        }
    }
}
