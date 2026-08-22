package com.p2pchat.daemon.json;

public record JsonString(String value) implements JsonValue {

    public JsonString {
        if (value == null) {
            throw new IllegalArgumentException("JsonString value cannot be null -- use JsonNull.INSTANCE instead");
        }
    }
}
