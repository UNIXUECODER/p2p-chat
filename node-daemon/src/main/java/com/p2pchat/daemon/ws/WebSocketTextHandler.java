package com.p2pchat.daemon.ws;

/**
 * M6d: what a caller implements to receive WebSocket activity. Deliberately generic — a raw
 * text string in, no JSON-RPC awareness at all, matching the same transport/protocol boundary
 * already held between M6c (generic JSON model) and the not-yet-built M6g (JSON-RPC method
 * surface, which will implement this interface). This class doesn't know or care whether {@code
 * text} is valid JSON, let alone valid JSON-RPC — that's entirely the implementer's problem.
 *
 * <p>{@code onConnect}/{@code onDisconnect} are default no-ops, not because they're unimportant
 * — M6g will need them to know which sessions exist for pushing {@code event.*} notifications —
 * but because a caller that only cares about receiving messages (e.g. a future test double)
 * shouldn't be forced to implement callbacks it has no use for.
 */
public interface WebSocketTextHandler {

    void onMessage(WebSocketSession session, String text);

    default void onConnect(WebSocketSession session) {
    }

    default void onDisconnect(WebSocketSession session) {
    }
}
