package com.p2pchat.daemon.json;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * A JSON number. Stores the validated raw text, not a pre-parsed {@code double} — a JSON number
 * can be an arbitrarily large integer (a 64-bit id, a file size in bytes), and {@code double}
 * cannot exactly represent every {@code long} beyond 2^53. Parsing eagerly into {@code double}
 * would silently corrupt exactly the values this project cares most about getting right.
 * Typed accessors below parse on demand instead, each failing loudly if the value doesn't
 * actually fit the requested type rather than silently truncating.
 */
public record JsonNumber(String raw) implements JsonValue {

    // RFC 8259 §6, exactly: optional '-', then '0' or a non-zero digit followed by more digits
    // (no leading zeros except a bare "0"), optional fraction (a '.' with at least one digit,
    // not before or after), optional exponent ('e'/'E', optional sign, at least one digit).
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    public JsonNumber {
        if (raw == null || !JSON_NUMBER.matcher(raw).matches()) {
            throw new IllegalArgumentException("Not a valid JSON number: " + raw);
        }
    }

    public static JsonNumber of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    public static JsonNumber of(int value) {
        return new JsonNumber(Integer.toString(value));
    }

    public static JsonNumber of(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON has no representation for NaN/Infinity: " + value);
        }
        return new JsonNumber(new BigDecimal(value).stripTrailingZeros().toPlainString());
    }

    public int asInt() {
        return Integer.parseInt(raw);
    }

    public long asLong() {
        return Long.parseLong(raw);
    }

    public double asDouble() {
        return Double.parseDouble(raw);
    }

    public BigDecimal asBigDecimal() {
        return new BigDecimal(raw);
    }
}
