package com.p2pchat.daemon.json;

/**
 * M6c: a JSON value — object, array, string, number, boolean, or null. The generic value model
 * this milestone builds; {@code JsonCodec} parses text into this tree and writes it back.
 *
 * <p><b>Not JSON-RPC-specific.</b> No {@code Request}/{@code Response}/method-dispatch types
 * live here — those are M6g's job, built on top of this. This is deliberately just "a JSON
 * value," reusable for whatever M6g, M6f, or anything later needs a JSON tree for.
 *
 * <p>Sibling top-level records, not nested inside this interface — matching {@code
 * ChatWireMessage}/{@code FileTransferMessage}'s established convention in this project rather
 * than inventing a new one.
 *
 * <p>The {@code asXxx()} accessors below are a narrowing convenience, not a coercion one — each
 * throws clearly if the runtime type doesn't match rather than attempting to convert (e.g.
 * {@code asLong()} on a {@code JsonString} throws; it does not try to parse the string as a
 * number). A future caller extracting fields from parsed params gets a clear failure at the
 * point of a real schema mismatch, not a silently wrong value three steps later.
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    default JsonObject asObject() {
        if (this instanceof JsonObject o) {
            return o;
        }
        throw new IllegalStateException("Expected a JSON object but found " + getClass().getSimpleName());
    }

    default JsonArray asArray() {
        if (this instanceof JsonArray a) {
            return a;
        }
        throw new IllegalStateException("Expected a JSON array but found " + getClass().getSimpleName());
    }

    default String asString() {
        if (this instanceof JsonString s) {
            return s.value();
        }
        throw new IllegalStateException("Expected a JSON string but found " + getClass().getSimpleName());
    }

    default int asInt() {
        if (this instanceof JsonNumber n) {
            return n.asInt();
        }
        throw new IllegalStateException("Expected a JSON number but found " + getClass().getSimpleName());
    }

    default long asLong() {
        if (this instanceof JsonNumber n) {
            return n.asLong();
        }
        throw new IllegalStateException("Expected a JSON number but found " + getClass().getSimpleName());
    }

    default double asDouble() {
        if (this instanceof JsonNumber n) {
            return n.asDouble();
        }
        throw new IllegalStateException("Expected a JSON number but found " + getClass().getSimpleName());
    }

    default boolean asBoolean() {
        if (this instanceof JsonBoolean b) {
            return b.value();
        }
        throw new IllegalStateException("Expected a JSON boolean but found " + getClass().getSimpleName());
    }

    default boolean isNull() {
        return this instanceof JsonNull;
    }
}
