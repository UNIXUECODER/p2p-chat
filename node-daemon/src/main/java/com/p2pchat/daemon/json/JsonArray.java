package com.p2pchat.daemon.json;

import java.util.ArrayList;
import java.util.List;

/** A JSON array. Order is meaningful and preserved, same reasoning as {@link JsonObject}. */
public record JsonArray(List<JsonValue> elements) implements JsonValue {

    public JsonArray {
        elements = List.copyOf(elements); // defensive copy, immutable
    }

    public static JsonArray of() {
        return new JsonArray(List.of());
    }

    public static JsonArray of(List<JsonValue> elements) {
        return new JsonArray(elements);
    }

    public int size() {
        return elements.size();
    }

    public JsonValue get(int index) {
        return elements.get(index);
    }

    /** Builder for programmatic construction. */
    public static final class Builder {
        private final List<JsonValue> elements = new ArrayList<>();

        public Builder add(JsonValue value) {
            elements.add(value);
            return this;
        }

        public JsonArray build() {
            return new JsonArray(elements);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
