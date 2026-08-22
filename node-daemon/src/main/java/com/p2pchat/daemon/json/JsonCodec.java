package com.p2pchat.daemon.json;

/**
 * M6c: parses JSON text into a {@link JsonValue} tree and writes one back. Hand-rolled, zero
 * dependency, matching every other wire format in this project ({@code RelayFrameCodec}, {@code
 * ChatMessageCodec}, {@code FileTransferMessageCodec}) rather than reaching for a library now
 * that the transport layer needs one too.
 *
 * <p><b>Deliberately strict, per RFC 8259</b> — this rejects things some lenient real-world
 * parsers accept: leading zeros in numbers ({@code 01}), a decimal point with no digit on either
 * side ({@code .5}, {@code 5.}), trailing commas, comments (not valid JSON, unlike JSON5),
 * unescaped control characters inside strings, and trailing non-whitespace content after the
 * top-level value. Matches this project's general "validate thoroughly, don't silently accept
 * malformed input" convention already established by every other codec here.
 *
 * <p><b>Max nesting depth ({@value #MAX_DEPTH}).</b> A recursive-descent parser — the natural
 * fit for JSON's own recursive grammar — will stack-overflow on adversarially deep input like
 * {@code "[[[[[[...]]]]]]"} thousands of levels deep. Genuinely security-relevant once this
 * parses untrusted input over a socket (M6d), not just theoretical.
 *
 * <p><b>Duplicate object keys: last value wins</b>, matching most real-world JSON parsers (the
 * spec itself leaves this unspecified) — see {@link JsonObject}'s own note.
 */
public final class JsonCodec {

    private static final int MAX_DEPTH = 32;

    private final String input;
    private int pos;

    private JsonCodec(String input) {
        this.input = input;
        this.pos = 0;
    }

    public static JsonValue parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Cannot parse null input");
        }
        JsonCodec parser = new JsonCodec(json);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.pos != json.length()) {
            throw new IllegalArgumentException(
                    "Trailing content after JSON value at position " + parser.pos + " of " + json.length());
        }
        return value;
    }

    public static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- parsing

    private JsonValue parseValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds max depth of " + MAX_DEPTH + " at position " + pos);
        }
        char c = peek();
        return switch (c) {
            case '{' -> parseObject(depth);
            case '[' -> parseArray(depth);
            case '"' -> new JsonString(parseStringLiteral());
            case 't', 'f' -> parseBooleanLiteral();
            case 'n' -> parseNullLiteral();
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield parseNumberLiteral();
                }
                throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
            }
        };
    }

    private JsonObject parseObject(int depth) {
        expect('{');
        skipWhitespace();
        var builder = JsonObject.builder();
        if (peek() == '}') {
            pos++;
            return builder.build();
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("Expected object key (a string) at position " + pos);
            }
            String key = parseStringLiteral();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            JsonValue value = parseValue(depth + 1);
            builder.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == '}') {
                pos++;
                return builder.build();
            }
            throw new IllegalArgumentException("Expected ',' or '}' at position " + pos + ", found '" + next + "'");
        }
    }

    private JsonArray parseArray(int depth) {
        expect('[');
        skipWhitespace();
        var builder = JsonArray.builder();
        if (peek() == ']') {
            pos++;
            return builder.build();
        }
        while (true) {
            skipWhitespace();
            builder.add(parseValue(depth + 1));
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == ']') {
                pos++;
                return builder.build();
            }
            throw new IllegalArgumentException("Expected ',' or ']' at position " + pos + ", found '" + next + "'");
        }
    }

    private String parseStringLiteral() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                sb.append(parseEscapeSequence());
                continue;
            }
            if (c < 0x20) {
                // RFC 8259 §7: control characters MUST be escaped, never appear literally.
                throw new IllegalArgumentException(
                        "Unescaped control character 0x" + Integer.toHexString(c) + " in string at position " + pos);
            }
            sb.append(c);
            pos++;
        }
    }

    private char parseEscapeSequence() {
        char c = peek();
        return switch (c) {
            case '"' -> consumeAndReturn('"');
            case '\\' -> consumeAndReturn('\\');
            case '/' -> consumeAndReturn('/');
            case 'b' -> consumeAndReturn('\b');
            case 'f' -> consumeAndReturn('\f');
            case 'n' -> consumeAndReturn('\n');
            case 'r' -> consumeAndReturn('\r');
            case 't' -> consumeAndReturn('\t');
            case 'u' -> parseUnicodeEscape();
            default -> throw new IllegalArgumentException("Invalid escape sequence '\\" + c + "' at position " + pos);
        };
    }

    private char consumeAndReturn(char literal) {
        pos++;
        return literal;
    }

    private char parseUnicodeEscape() {
        pos++; // consume 'u'
        if (pos + 4 > input.length()) {
            throw new IllegalArgumentException("Truncated \\u escape at position " + pos);
        }
        String hex = input.substring(pos, pos + 4);
        int codeUnit;
        try {
            codeUnit = Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid \\u escape '" + hex + "' at position " + pos);
        }
        pos += 4;
        // Deliberately NOT manually recombining surrogate pairs -- Java strings are already
        // UTF-16, the same representation backslash-u escapes describe, so decoding each one as
        // a single char and appending both halves in sequence reproduces the correct surrogate
        // pair automatically. Manually combining them would be solving a problem that doesn't
        // exist at this layer, and is the actual common source of bugs in hand-rolled JSON
        // parsers.
        return (char) codeUnit;
    }

    private JsonNumber parseNumberLiteral() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++; // a bare "0" -- must NOT be followed by further digits (no leading zeros)
        } else {
            requireDigit();
            while (pos < input.length() && isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            requireDigit(); // at least one digit after the decimal point
            while (pos < input.length() && isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                pos++;
            }
            requireDigit(); // at least one digit in the exponent
            while (pos < input.length() && isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        return new JsonNumber(input.substring(start, pos));
    }

    private void requireDigit() {
        if (pos >= input.length() || !isDigit(input.charAt(pos))) {
            throw new IllegalArgumentException("Expected a digit at position " + pos);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private JsonBoolean parseBooleanLiteral() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return JsonBoolean.TRUE;
        }
        if (input.startsWith("false", pos)) {
            pos += 5;
            return JsonBoolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid literal at position " + pos + " (expected 'true' or 'false')");
    }

    private JsonNull parseNullLiteral() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return JsonNull.INSTANCE;
        }
        throw new IllegalArgumentException("Invalid literal at position " + pos + " (expected 'null')");
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= input.length()) {
            throw new IllegalArgumentException("Unexpected end of input at position " + pos);
        }
        return input.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos + ", found '" + peek() + "'");
        }
        pos++;
    }

    // ---------------------------------------------------------------- writing

    private static void writeValue(JsonValue value, StringBuilder sb) {
        switch (value) {
            case JsonObject object -> writeObject(object, sb);
            case JsonArray array -> writeArray(array, sb);
            case JsonString string -> writeStringLiteral(string.value(), sb);
            case JsonNumber number -> sb.append(number.raw()); // already-validated JSON number text
            case JsonBoolean bool -> sb.append(bool.value() ? "true" : "false");
            case JsonNull ignored -> sb.append("null");
        }
    }

    private static void writeObject(JsonObject object, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (var entry : object.members().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeStringLiteral(entry.getKey(), sb);
            sb.append(':');
            writeValue(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeArray(JsonArray array, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (JsonValue element : array.elements()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(element, sb);
        }
        sb.append(']');
    }

    private static void writeStringLiteral(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c); // forward slash and non-ASCII characters need no escaping
                    }
                }
            }
        }
        sb.append('"');
    }
}
