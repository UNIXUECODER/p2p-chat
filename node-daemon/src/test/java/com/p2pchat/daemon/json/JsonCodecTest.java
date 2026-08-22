package com.p2pchat.daemon.json;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6c. Pure JDK — {@code JsonValue}/{@code JsonCodec} have zero external dependency — so unlike
 * every crypto- or network-adjacent test in this project, this one is genuinely fully verified
 * here: compiled and run directly in the sandbox that produced it, nothing hand-traced, nothing
 * stub-compiled, and nothing left for a real build to confirm.
 */
class JsonCodecTest {

    @Nested
    class RoundTrip {

        @Test
        void object() {
            JsonValue value = JsonCodec.parse("{\"a\":1,\"b\":\"two\",\"c\":true,\"d\":null}");

            assertThat(value).isInstanceOf(JsonObject.class);
            JsonObject object = (JsonObject) value;
            assertThat(((JsonNumber) object.get("a")).asInt()).isEqualTo(1);
            assertThat(((JsonString) object.get("b")).value()).isEqualTo("two");
            assertThat(((JsonBoolean) object.get("c")).value()).isTrue();
            assertThat(object.get("d")).isEqualTo(JsonNull.INSTANCE);
        }

        @Test
        void nestedObjectAndArray_matchingSection7sRealPushEventShape() {
            // Not an invented example -- architecture-spec.md §7's event.message.received.
            String json = "{\"jsonrpc\":\"2.0\",\"method\":\"event.message.received\"," +
                    "\"params\":{\"message\":{\"messageId\":\"m_7bd2\",\"conversationId\":\"c_9f2a\"," +
                    "\"senderPeerId\":\"12D3Koo\",\"contentType\":\"text/plain\",\"content\":\"hey back\"}}}";

            JsonObject root = (JsonObject) JsonCodec.parse(json);
            JsonObject params = (JsonObject) root.get("params");
            JsonObject message = (JsonObject) params.get("message");

            assertThat(((JsonString) message.get("messageId")).value()).isEqualTo("m_7bd2");
            assertThat(((JsonString) root.get("method")).value()).isEqualTo("event.message.received");
        }

        @Test
        void arrayOfObjects() {
            JsonValue value = JsonCodec.parse("[{\"x\":1},{\"x\":2},{\"x\":3}]");

            JsonArray array = (JsonArray) value;
            assertThat(array.size()).isEqualTo(3);
            assertThat(((JsonNumber) ((JsonObject) array.get(1)).get("x")).asInt()).isEqualTo(2);
        }

        @Test
        void writeThenParseProducesAnEquivalentTree() {
            JsonObject original = JsonObject.builder()
                    .put("name", "hey")
                    .put("count", 42L)
                    .put("ratio", 3.5)
                    .put("active", true)
                    .put("tags", JsonArray.builder().add(new JsonString("a")).add(new JsonString("b")).build())
                    .build();

            String written = JsonCodec.write(original);
            JsonValue reparsed = JsonCodec.parse(written);

            assertThat(reparsed).isEqualTo(original);
        }

        @Test
        void insertionOrderIsPreservedBothWays() {
            JsonObject parsed = (JsonObject) JsonCodec.parse("{\"z\":1,\"a\":2,\"m\":3}");
            assertThat(parsed.members().keySet()).containsExactly("z", "a", "m");

            JsonObject built = JsonObject.builder().put("z", 1L).put("a", 2L).put("m", 3L).build();
            assertThat(JsonCodec.write(built)).isEqualTo("{\"z\":1,\"a\":2,\"m\":3}");
        }
    }

    @Nested
    class NumberPrecision {

        @Test
        void largeLongSurvivesRoundTripExactly() {
            // The actual pitfall this design avoids: a double-based number model would silently
            // corrupt this. 9007199254740993 is 2^53 + 1, the smallest long a double cannot
            // represent exactly.
            long value = 9_007_199_254_740_993L;

            JsonNumber number = (JsonNumber) JsonCodec.parse(Long.toString(value));

            assertThat(number.asLong()).isEqualTo(value);
        }

        @Test
        void negativeZeroIsValid() {
            assertThat(((JsonNumber) JsonCodec.parse("-0")).asDouble()).isEqualTo(-0.0);
        }

        @Test
        void exponentFormRoundTrips() {
            assertThat(((JsonNumber) JsonCodec.parse("1.5e10")).asDouble()).isEqualTo(1.5e10);
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "1.", ".5", "1.5e", "1.5e+", "--1", "1-2"})
        void rejectsInvalidNumberGrammar(String malformed) {
            assertThatThrownBy(() -> JsonCodec.parse(malformed)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void asIntThrowsClearlyWhenTheValueDoesNotFit() {
            JsonNumber tooLarge = (JsonNumber) JsonCodec.parse("99999999999999");

            assertThatThrownBy(tooLarge::asInt).isInstanceOf(NumberFormatException.class);
        }
    }

    @Nested
    class StringEscaping {

        @Test
        void standardEscapesRoundTrip() {
            String original = "line1\nline2\ttab\"quote\\backslash";

            String written = JsonCodec.write(new JsonString(original));
            JsonString reparsed = (JsonString) JsonCodec.parse(written);

            assertThat(reparsed.value()).isEqualTo(original);
        }

        @Test
        void unicodeEscapeInBmp() {
            JsonString value = (JsonString) JsonCodec.parse("\"\\u00e9\""); // e-acute

            assertThat(value.value()).isEqualTo("\u00e9");
        }

        @Test
        void surrogatePairEscapeProducesTheCorrectCharacter() {
            // U+1F600 (grinning face) as a UTF-16 surrogate pair: the exact case a naive
            // hand-rolled parser gets wrong by trying to manually recombine the pair.
            JsonString value = (JsonString) JsonCodec.parse("\"\\ud83d\\ude00\"");

            assertThat(value.value()).isEqualTo("\ud83d\ude00");
            assertThat(value.value().codePointAt(0)).isEqualTo(0x1F600);
        }

        @Test
        void controlCharacterEscapesOnWriteAndRoundTrips() {
            String original = "\u0001\u0002";

            String written = JsonCodec.write(new JsonString(original));

            assertThat(written).isEqualTo("\"\\u0001\\u0002\"");
            assertThat(((JsonString) JsonCodec.parse(written)).value()).isEqualTo(original);
        }

        @Test
        void unescapedControlCharacterInInputIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("\"a\u0001b\"")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void unterminatedStringIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("\"unterminated")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void invalidEscapeSequenceIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("\"\\q\"")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class NarrowingAccessors {

        @Test
        void correctTypeAccessorsSucceed() {
            JsonObject object = (JsonObject) JsonCodec.parse(
                    "{\"s\":\"hi\",\"n\":42,\"b\":true,\"o\":{},\"a\":[],\"z\":null}");

            assertThat(object.get("s").asString()).isEqualTo("hi");
            assertThat(object.get("n").asInt()).isEqualTo(42);
            assertThat(object.get("n").asLong()).isEqualTo(42L);
            assertThat(object.get("b").asBoolean()).isTrue();
            assertThat(object.get("o").asObject()).isEqualTo(JsonObject.of());
            assertThat(object.get("a").asArray()).isEqualTo(JsonArray.of());
            assertThat(object.get("z").isNull()).isTrue();
        }

        @Test
        void wrongTypeAccessorThrowsRatherThanCoercing() {
            JsonValue string = new JsonString("42");

            // Deliberately not coercive -- a JsonString holding "42" does not silently become
            // the number 42 just because asLong() was called on it.
            assertThatThrownBy(string::asLong).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class MalformedInputRejection {

        @Test
        void trailingContentAfterTopLevelValueIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("{}garbage")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void trailingCommaInObjectIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("{\"a\":1,}")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void trailingCommaInArrayIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("[1,2,]")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void javaScriptStyleCommentIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("{ // comment\n}")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void emptyInputIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullInputIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void unquotedObjectKeyIsRejected() {
            assertThatThrownBy(() -> JsonCodec.parse("{a:1}")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void duplicateKeysLastValueWins() {
            JsonObject object = (JsonObject) JsonCodec.parse("{\"a\":1,\"a\":2}");

            assertThat(((JsonNumber) object.get("a")).asInt()).isEqualTo(2);
            assertThat(object.members()).hasSize(1);
        }

        @Test
        void deeplyNestedInputBeyondMaxDepthIsRejectedNotStackOverflow() {
            String deeplyNested = "[".repeat(2000) + "]".repeat(2000);

            assertThatThrownBy(() -> JsonCodec.parse(deeplyNested)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void topLevelScalarIsAccepted_perCurrentRfc8259NotOlderRfc4627() {
            assertThat(((JsonNumber) JsonCodec.parse("42")).asInt()).isEqualTo(42);
            assertThat(((JsonString) JsonCodec.parse("\"hi\"")).value()).isEqualTo("hi");
            assertThat(JsonCodec.parse("true")).isEqualTo(JsonBoolean.TRUE);
            assertThat(JsonCodec.parse("null")).isEqualTo(JsonNull.INSTANCE);
        }

        @Test
        void whitespaceAroundTopLevelValueIsIgnored() {
            assertThat(JsonCodec.parse("  \n\t {}  \n")).isEqualTo(JsonObject.of());
        }
    }
}
