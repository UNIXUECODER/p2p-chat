package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonCodec;
import com.p2pchat.daemon.json.JsonNull;
import com.p2pchat.daemon.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRpcRequestTest {

    @Test
    void parsesAWellFormedRequestWithStringId() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"identity.get\",\"params\":{}}"));

        assertThat(request.method()).isEqualTo("identity.get");
        assertThat(request.isNotification()).isFalse();
        assertThat(request.id().asString()).isEqualTo("abc");
    }

    @Test
    void parsesAWellFormedRequestWithNumericId() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"network.status\"}"));

        assertThat(request.id().asLong()).isEqualTo(17L);
    }

    @Test
    void absentIdMeansNotification() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"contacts.list\"}"));

        assertThat(request.isNotification()).isTrue();
        assertThat(request.id()).isNull();
    }

    @Test
    void explicitJsonNullIdIsNotANotification() throws Exception {
        // JSON-RPC 2.0 §4.1 distinguishes "id member absent" (notification) from "id member
        // present with a null value" (still a real request, expecting id: null echoed back) --
        // see JsonRpcRequest's own Javadoc for why this project honors that distinction exactly.
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"contacts.list\"}"));

        assertThat(request.isNotification()).isFalse();
        assertThat(request.id()).isInstanceOf(JsonNull.class);
    }

    @Test
    void absentParamsDefaultsToEmptyObject() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.list\"}"));

        assertThat(request.params().has("anything")).isFalse();
    }

    @Test
    void nullParamsDefaultsToEmptyObject() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.list\",\"params\":null}"));

        assertThat(request.params().has("anything")).isFalse();
    }

    @Test
    void topLevelNonObjectIsInvalidRequestWithNullBestEffortId() {
        assertThatThrownBy(() -> JsonRpcRequest.parse(JsonCodec.parse("[1,2,3]")))
                .isInstanceOf(JsonRpcRequestException.class)
                .satisfies(e -> assertThat(((JsonRpcRequestException) e).bestEffortId()).isNull());
    }

    @Test
    void missingJsonrpcFieldIsRejected() {
        JsonValue raw = JsonCodec.parse("{\"id\":1,\"method\":\"contacts.list\"}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw)).isInstanceOf(JsonRpcRequestException.class);
    }

    @Test
    void wrongJsonrpcVersionIsRejected() {
        JsonValue raw = JsonCodec.parse("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"contacts.list\"}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw)).isInstanceOf(JsonRpcRequestException.class);
    }

    @Test
    void missingJsonrpcFieldStillRecoversAValidId() {
        // A malformed envelope with an otherwise perfectly good id should still echo that id back
        // on its error response -- see JsonRpcRequestException's own Javadoc.
        JsonValue raw = JsonCodec.parse("{\"id\":\"keep-me\",\"method\":\"contacts.list\"}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw))
                .isInstanceOf(JsonRpcRequestException.class)
                .satisfies(e -> assertThat(((JsonRpcRequestException) e).bestEffortId().asString()).isEqualTo("keep-me"));
    }

    @Test
    void missingMethodIsRejected() {
        JsonValue raw = JsonCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw)).isInstanceOf(JsonRpcRequestException.class);
    }

    @Test
    void nonStringMethodIsRejected() {
        JsonValue raw = JsonCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":42}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw)).isInstanceOf(JsonRpcRequestException.class);
    }

    @Test
    void arrayFormParamsIsRejected() {
        // Positional params are spec-legal but genuinely unsupported by every method this project
        // defines -- see JsonRpcRequest's own Javadoc.
        JsonValue raw = JsonCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contacts.list\",\"params\":[1,2]}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw)).isInstanceOf(JsonRpcRequestException.class);
    }

    @Test
    void nonObjectNonArrayIdIsRejectedAndFallsBackToNullBestEffortId() {
        JsonValue raw = JsonCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":{\"nested\":true},\"method\":\"contacts.list\"}");
        assertThatThrownBy(() -> JsonRpcRequest.parse(raw))
                .isInstanceOf(JsonRpcRequestException.class)
                .satisfies(e -> assertThat(((JsonRpcRequestException) e).bestEffortId()).isNull());
    }

    // ---------------------------------------------------------------- params field accessors

    @Test
    void requireStringReturnsThePresentValue() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"conversationId\":\"c1\"}}"));

        assertThat(request.requireString("conversationId")).isEqualTo("c1");
    }

    @Test
    void requireStringThrowsWhenFieldIsAbsent() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}"));

        assertThatThrownBy(() -> request.requireString("conversationId")).isInstanceOf(JsonRpcParamException.class);
    }

    @Test
    void requireStringThrowsWhenFieldIsWrongType() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"conversationId\":123}}"));

        assertThatThrownBy(() -> request.requireString("conversationId")).isInstanceOf(JsonRpcParamException.class);
    }

    @Test
    void optionalStringFallsBackWhenAbsent() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}"));

        assertThat(request.optionalString("contentType", "text/plain")).isEqualTo("text/plain");
    }

    @Test
    void optionalStringThrowsOnWrongTypeRatherThanSilentlyFallingBack() throws Exception {
        // A present-but-wrong-typed optional field is treated as a real client bug, not silently
        // masked by the default -- see JsonRpcParamException's own Javadoc for the reasoning.
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"contentType\":42}}"));

        assertThatThrownBy(() -> request.optionalString("contentType", "text/plain"))
                .isInstanceOf(JsonRpcParamException.class);
    }

    @Test
    void requireIntAndOptionalIntWork() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.parse(JsonCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"limit\":25}}"));

        assertThat(request.requireInt("limit")).isEqualTo(25);
        assertThat(request.optionalInt("cursor", 50)).isEqualTo(50);
    }
}
