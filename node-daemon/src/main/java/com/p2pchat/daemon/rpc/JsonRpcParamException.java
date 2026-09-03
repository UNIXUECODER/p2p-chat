package com.p2pchat.daemon.rpc;

/**
 * Thrown by {@link JsonRpcRequest}'s {@code requireXxx}/{@code optionalXxx} field accessors when
 * a specific {@code params} field a method needs is missing (for a required field) or present
 * with the wrong JSON type (required or optional alike — see {@link JsonRpcRequest}'s own Javadoc
 * for why a wrong-typed optional field fails loud rather than silently falling back to a
 * default). Deliberately distinct from {@link JsonRpcRequestException} — see that class's own
 * Javadoc for the full reasoning; in short, this always fires against an already-valid {@link
 * JsonRpcRequest} with a known id, so it carries no id of its own.
 */
public final class JsonRpcParamException extends Exception {

    public JsonRpcParamException(String message) {
        super(message);
    }
}
