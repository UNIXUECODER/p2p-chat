package com.p2pchat.messaging.wire;

import com.p2pchat.messaging.HlcTimestamp;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageCodecTest {

    private static final String MESSAGE_ID = UUID.randomUUID().toString();
    private static final String REPLY_TO_ID = UUID.randomUUID().toString();
    private static final String SENDER_ADDRESS = "/ip4/127.0.0.1/tcp/9000/p2p/16Uiu2HAmDemoSenderPeerId";
    private static final HlcTimestamp HLC = new HlcTimestamp(1_770_000_000_123L, 7, "12D3KooWSenderNode");

    @Test
    void chatMessageRoundTripWithReply() {
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain",
                "Hello, world!".getBytes(StandardCharsets.UTF_8), REPLY_TO_ID
        );

        byte[] wire = ChatMessageCodec.encode(original);
        ChatWireMessage decoded = ChatMessageCodec.decode(wire);

        assertThat(decoded).isInstanceOf(ChatMessagePayload.class);
        ChatMessagePayload decodedChat = (ChatMessagePayload) decoded;
        assertThat(decodedChat.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(decodedChat.senderAddress()).isEqualTo(SENDER_ADDRESS);
        assertThat(decodedChat.hlcTimestamp()).isEqualTo(HLC); // proves M5a's round trip survives M5b's wire layer too
        assertThat(decodedChat.conversationId()).isEqualTo("conv-1");
        assertThat(decodedChat.contentType()).isEqualTo("text/plain");
        assertThat(decodedChat.content()).isEqualTo("Hello, world!".getBytes(StandardCharsets.UTF_8));
        assertThat(decodedChat.replyToMessageId()).isEqualTo(REPLY_TO_ID);
    }

    @Test
    void chatMessageRoundTripWithoutReply() {
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain",
                "No reply here".getBytes(StandardCharsets.UTF_8), null
        );

        byte[] wire = ChatMessageCodec.encode(original);
        ChatMessagePayload decoded = (ChatMessagePayload) ChatMessageCodec.decode(wire);

        assertThat(decoded.replyToMessageId()).isNull();
    }

    @Test
    void chatMessageRoundTripWithUnicodeContent() {
        String unicode = "こんにちは 👋 café naïve";
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain",
                unicode.getBytes(StandardCharsets.UTF_8), null
        );

        byte[] wire = ChatMessageCodec.encode(original);
        ChatMessagePayload decoded = (ChatMessagePayload) ChatMessageCodec.decode(wire);

        assertThat(new String(decoded.content(), StandardCharsets.UTF_8)).isEqualTo(unicode);
    }

    @Test
    void chatMessageRoundTripWithEmptyContent() {
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain", new byte[0], null
        );

        byte[] wire = ChatMessageCodec.encode(original);
        ChatMessagePayload decoded = (ChatMessagePayload) ChatMessageCodec.decode(wire);

        assertThat(decoded.content()).isEmpty();
    }

    @Test
    void deliveryReceiptRoundTrip() {
        DeliveryReceiptPayload original = new DeliveryReceiptPayload("conv-1", MESSAGE_ID);

        byte[] wire = ChatMessageCodec.encode(original);
        ChatWireMessage decoded = ChatMessageCodec.decode(wire);

        assertThat(decoded).isInstanceOf(DeliveryReceiptPayload.class);
        DeliveryReceiptPayload decodedReceipt = (DeliveryReceiptPayload) decoded;
        assertThat(decodedReceipt.conversationId()).isEqualTo("conv-1");
        assertThat(decodedReceipt.messageId()).isEqualTo(MESSAGE_ID);
    }

    @Test
    void readReceiptRoundTrip() {
        ReadReceiptPayload original = new ReadReceiptPayload("conv-1", HLC);

        byte[] wire = ChatMessageCodec.encode(original);
        ChatWireMessage decoded = ChatMessageCodec.decode(wire);

        assertThat(decoded).isInstanceOf(ReadReceiptPayload.class);
        ReadReceiptPayload decodedReceipt = (ReadReceiptPayload) decoded;
        assertThat(decodedReceipt.conversationId()).isEqualTo("conv-1");
        assertThat(decodedReceipt.readUpToHlcTimestamp()).isEqualTo(HLC);
    }

    @Test
    void rejectInvalidMarker() {
        byte[] badWire = new byte[]{0x00, 0x01, 0x02};
        assertThatThrownBy(() -> ChatMessageCodec.decode(badWire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown chat message marker");
    }

    // --- Validation: records reject malformed construction before the codec is ever involved ---

    @Test
    void chatMessagePayloadRejectsNonUuidMessageId() {
        assertThatThrownBy(() -> new ChatMessagePayload("not-a-uuid", SENDER_ADDRESS, HLC, "conv-1", "text/plain", new byte[0], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    void chatMessagePayloadRejectsNonUuidReplyToMessageId() {
        assertThatThrownBy(() -> new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain", new byte[0], "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replyToMessageId");
    }

    @Test
    void chatMessagePayloadRejectsEmptyConversationId() {
        assertThatThrownBy(() -> new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, HLC, "", "text/plain", new byte[0], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
    }

    @Test
    void chatMessagePayloadRejectsEmptySenderAddress() {
        assertThatThrownBy(() -> new ChatMessagePayload(MESSAGE_ID, "", HLC, "conv-1", "text/plain", new byte[0], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("senderAddress");
    }

    @Test
    void chatMessagePayloadRejectsNullHlcTimestamp() {
        assertThatThrownBy(() -> new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, null, "conv-1", "text/plain", new byte[0], null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliveryReceiptPayloadRejectsNonUuidMessageId() {
        assertThatThrownBy(() -> new DeliveryReceiptPayload("conv-1", "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    void readReceiptPayloadRejectsEmptyConversationId() {
        assertThatThrownBy(() -> new ReadReceiptPayload("", HLC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
    }

    // --- Pre-M6 cleanup pass: length-prefixed fields (messageId, senderAddress, content, ...)
    // had no bounds check at all — same gap RelayFrameCodec had (see its own test/Javadoc), fixed
    // the same way here. Marker validation was already correct (predates this pass). ---

    @Test
    void rejectOversizedFieldLength() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 2); // CHAT_MESSAGE_MARKER
        buf.putInt(Integer.MAX_VALUE); // messageId's claimed length
        assertThatThrownBy(() -> ChatMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectNegativeFieldLength() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 2); // CHAT_MESSAGE_MARKER
        buf.putInt(-1);
        assertThatThrownBy(() -> ChatMessageCodec.decode(buf.array()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed length-prefixed field");
    }

    @Test
    void rejectEmptyWire() {
        assertThatThrownBy(() -> ChatMessageCodec.decode(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chat message too short");
    }
}
