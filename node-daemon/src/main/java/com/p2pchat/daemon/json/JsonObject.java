package com.p2pchat.daemon.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JSON object. Backed by {@link LinkedHashMap} deliberately — preserves insertion order both
 * ways: the order keys appeared in parsed input, and the order a caller adds them when building
 * one programmatically. Not required by the JSON spec, but predictable output is worth the (free)
 * cost, and matches what most real JSON libraries already do.
 *
 * <p><b>Duplicate keys during parsing: last value wins.</b> The JSON spec (RFC 8259) leaves this
 * unspecified — this is a deliberate choice, not an accident, matching the most common real
 * behavior (JavaScript's {@code JSON.parse}, Jackson's default).
 */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

    public JsonObject {
        // NOT Map.copyOf(members) -- caught by actually running the insertion-order test, not by
        // reading this line: Map.copyOf's own documentation does not guarantee preserving
        // iteration order, and its real implementation (ImmutableCollections.MapN) does not.
        // That would have silently thrown away the entire reason LinkedHashMap was chosen here.
        // A fresh LinkedHashMap copy, wrapped unmodifiable, actually preserves it.
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public static JsonObject of() {
        return new JsonObject(new LinkedHashMap<>());
    }

    public JsonValue get(String key) {
        return members.get(key);
    }

    public boolean has(String key) {
        return members.containsKey(key);
    }

    /** Builder for programmatic construction — insertion order preserved, same as parsing. */
    public static final class Builder {
        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

        public Builder put(String key, JsonValue value) {
            members.put(key, value);
            return this;
        }

        public Builder put(String key, String value) {
            return put(key, value == null ? JsonNull.INSTANCE : new JsonString(value));
        }

        public Builder put(String key, long value) {
            return put(key, JsonNumber.of(value));
        }

        public Builder put(String key, double value) {
            return put(key, JsonNumber.of(value));
        }

        public Builder put(String key, boolean value) {
            return put(key, JsonBoolean.of(value));
        }

        public JsonObject build() {
            return new JsonObject(members);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
