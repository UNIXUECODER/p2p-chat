package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.json.JsonCodec;
import com.p2pchat.daemon.json.JsonNull;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonString;
import com.p2pchat.daemon.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcResponseTest {

    @Test
    void successSerializesResultUnderJsonrpc20Envelope() {
        JsonRpcResponse response = new JsonRpcResponse.Success(new JsonString("req-1"),
                JsonObject.builder().put("messageId", "m-1").build());

        JsonValue parsedBack = JsonCodec.parse(response.toJsonText());
        JsonObject object = parsedBack.asObject();
        assertThat(object.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(object.get("id").asString()).isEqualTo("req-1");
        assertThat(object.get("result").asObject().get("messageId").asString()).isEqualTo("m-1");
        assertThat(object.has("error")).isFalse();
    }

    @Test
    void errorSerializesCodeAndMessageUnderJsonrpc20Envelope() {
        JsonRpcResponse response = new JsonRpcResponse.Error(new JsonString("req-2"),
                JsonRpcError.of(DaemonErrorCode.PEER_UNREACHABLE, "no route to peer"));

        JsonObject object = JsonCodec.parse(response.toJsonText()).asObject();
        assertThat(object.get("id").asString()).isEqualTo("req-2");
        assertThat(object.get("error").asObject().get("code").asLong()).isEqualTo(-32000L);
        assertThat(object.get("error").asObject().get("message").asString()).isEqualTo("no route to peer");
        assertThat(object.has("result")).isFalse();
    }

    @Test
    void nullIdSerializesAsJsonNullNotAMissingField() {
        // JSON-RPC 2.0 §5: an id that couldn't be determined MUST serialize as null, not be
        // omitted entirely -- a client parsing this response still needs an "id" key to look at.
        JsonRpcResponse response = new JsonRpcResponse.Error(null, JsonRpcError.parseError());

        JsonObject object = JsonCodec.parse(response.toJsonText()).asObject();
        assertThat(object.has("id")).isTrue();
        assertThat(object.get("id")).isInstanceOf(JsonNull.class);
    }

    @Test
    void errorDataFieldIsOmittedWhenAbsent() {
        JsonRpcResponse response = new JsonRpcResponse.Error(new JsonString("x"),
                new JsonRpcError(-32000, "no data here"));

        JsonObject error = JsonCodec.parse(response.toJsonText()).asObject().get("error").asObject();
        assertThat(error.has("data")).isFalse();
    }

    @Test
    void errorDataFieldIsIncludedWhenPresent() {
        JsonRpcResponse response = new JsonRpcResponse.Error(new JsonString("x"),
                new JsonRpcError(-32000, "with data", JsonObject.builder().put("field", "detail").build()));

        JsonObject error = JsonCodec.parse(response.toJsonText()).asObject().get("error").asObject();
        assertThat(error.has("data")).isTrue();
        assertThat(error.get("data").asObject().get("field").asString()).isEqualTo("detail");
    }
}
