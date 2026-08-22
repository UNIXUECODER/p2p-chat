package com.p2pchat.daemon.json;

/** JSON {@code null}. A singleton, not one instance per occurrence -- there's only one null. */
public record JsonNull() implements JsonValue {

    public static final JsonNull INSTANCE = new JsonNull();
}
