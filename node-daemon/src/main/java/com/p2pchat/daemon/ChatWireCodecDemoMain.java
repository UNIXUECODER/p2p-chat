package com.p2pchat.daemon;

import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.messaging.wire.ChatWireMessage;
import com.p2pchat.messaging.wire.DeliveryReceiptPayload;
import com.p2pchat.messaging.wire.ReadReceiptPayload;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * M5b: encode/decode round-trip checks for the three chat wire payloads (ChatMessagePayload,
 * DeliveryReceiptPayload, ReadReceiptPayload), proven in isolation before any of it is wired to
 * a real connection — same reasoning as M4b's {@code WireCodecDemoMain}.
 *
 * Covers normal cases, an optional field present/absent, Unicode content, an empty-content edge
 * case, an {@link HlcTimestamp} surviving the wire round trip intact, and validation checks
 * (non-UUID {@code messageId}, empty {@code conversationId}, empty {@code senderAddress}, null
 * {@code hlcTimestamp}) rejected at construction time, before the codec is ever involved.
 *
 * <p>{@code senderAddress} was added during M5c, correcting this payload — see
 * {@code ChatMessagePayload}'s own Javadoc and the M5c section of README.md for why.
 */
public class ChatWireCodecDemoMain {

    private static int passed = 0;
    private static int failed = 0;

    private static final String MESSAGE_ID = UUID.randomUUID().toString();
    private static final String REPLY_TO_ID = UUID.randomUUID().toString();
    private static final String SENDER_ADDRESS = "/ip4/127.0.0.1/tcp/9000/p2p/16Uiu2HAmDemoSenderPeerId";
    private static final HlcTimestamp HLC = new HlcTimestamp(1_770_000_000_123L, 7, "12D3KooWSenderNode");

    public static void main(String[] args) {
        testChatMessageWithReply();
        testChatMessageWithoutReply();
        testChatMessageUnicodeContent();
        testChatMessageEmptyContent();
        testDeliveryReceipt();
        testReadReceipt();
        testInvalidMarkerRejected();
        testNonUuidMessageIdRejected();
        testNonUuidReplyToIdRejected();
        testEmptyConversationIdRejected();
        testEmptySenderAddressRejected();
        testNullHlcTimestampRejected();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.out.println();
        System.out.println(failed == 0
                ? "M5b CONFIRMED: all three chat wire payloads encode/decode correctly, including edge cases and validation."
                : "M5b FAILED: see the [FAIL] lines above.");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testChatMessageWithReply() {
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain",
                "Hello, world!".getBytes(StandardCharsets.UTF_8), REPLY_TO_ID);

        byte[] wire = ChatMessageCodec.encode(original);
        ChatWireMessage decoded = ChatMessageCodec.decode(wire);

        check("ChatMessage decodes to correct type", decoded instanceof ChatMessagePayload);
        ChatMessagePayload result = (ChatMessagePayload) decoded;
        check("ChatMessage.messageId", result.messageId().equals(original.messageId()));
        check("ChatMessage.senderAddress", result.senderAddress().equals(original.senderAddress()));
        check("ChatMessage.hlcTimestamp survives the wire round trip", result.hlcTimestamp().equals(original.hlcTimestamp()));
        check("ChatMessage.conversationId", result.conversationId().equals(original.conversationId()));
        check("ChatMessage.contentType", result.contentType().equals(original.contentType()));
        check("ChatMessage.content bytes", Arrays.equals(result.content(), original.content()));
        check("ChatMessage.replyToMessageId", result.replyToMessageId().equals(original.replyToMessageId()));
    }

    private static void testChatMessageWithoutReply() {
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain",
                "No reply here".getBytes(StandardCharsets.UTF_8), null);

        byte[] wire = ChatMessageCodec.encode(original);
        ChatMessagePayload result = (ChatMessagePayload) ChatMessageCodec.decode(wire);
        check("ChatMessage without a reply round-trips replyToMessageId as null", result.replyToMessageId() == null);
    }

    private static void testChatMessageUnicodeContent() {
        String unicode = "\u3053\u3093\u306b\u3061\u306f \ud83d\udc4b caf\u00e9 na\u00efve";
        ChatMessagePayload original = new ChatMessagePayload(
                MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain", unicode.getBytes(StandardCharsets.UTF_8), null);

        byte[] wire = ChatMessageCodec.encode(original);
        ChatMessagePayload result = (ChatMessagePayload) ChatMessageCodec.decode(wire);
        check("ChatMessage Unicode content round-trips", new String(result.content(), StandardCharsets.UTF_8).equals(unicode));
    }

    private static void testChatMessageEmptyContent() {
        ChatMessagePayload original = new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain", new byte[0], null);
        byte[] wire = ChatMessageCodec.encode(original);
        ChatMessagePayload result = (ChatMessagePayload) ChatMessageCodec.decode(wire);
        check("ChatMessage empty content round-trips", result.content().length == 0);
    }

    private static void testDeliveryReceipt() {
        DeliveryReceiptPayload original = new DeliveryReceiptPayload("conv-1", MESSAGE_ID);
        byte[] wire = ChatMessageCodec.encode(original);
        ChatWireMessage decoded = ChatMessageCodec.decode(wire);

        check("DeliveryReceipt decodes to correct type", decoded instanceof DeliveryReceiptPayload);
        DeliveryReceiptPayload result = (DeliveryReceiptPayload) decoded;
        check("DeliveryReceipt.conversationId", result.conversationId().equals("conv-1"));
        check("DeliveryReceipt.messageId", result.messageId().equals(MESSAGE_ID));
    }

    private static void testReadReceipt() {
        ReadReceiptPayload original = new ReadReceiptPayload("conv-1", HLC);
        byte[] wire = ChatMessageCodec.encode(original);
        ChatWireMessage decoded = ChatMessageCodec.decode(wire);

        check("ReadReceipt decodes to correct type", decoded instanceof ReadReceiptPayload);
        ReadReceiptPayload result = (ReadReceiptPayload) decoded;
        check("ReadReceipt.conversationId", result.conversationId().equals("conv-1"));
        check("ReadReceipt.readUpToHlcTimestamp (watermark) round-trips", result.readUpToHlcTimestamp().equals(HLC));
    }

    private static void testInvalidMarkerRejected() {
        byte[] badWire = new byte[]{0x00, 0x01, 0x02};
        boolean threw = false;
        try {
            ChatMessageCodec.decode(badWire);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Unknown marker byte rejected at decode time", threw);
    }

    private static void testNonUuidMessageIdRejected() {
        boolean threw = false;
        try {
            new ChatMessagePayload("not-a-uuid", SENDER_ADDRESS, HLC, "conv-1", "text/plain", new byte[0], null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Non-UUID messageId rejected at construction", threw);
    }

    private static void testNonUuidReplyToIdRejected() {
        boolean threw = false;
        try {
            new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, HLC, "conv-1", "text/plain", new byte[0], "not-a-uuid");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Non-UUID replyToMessageId rejected at construction", threw);
    }

    private static void testEmptyConversationIdRejected() {
        boolean threw = false;
        try {
            new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, HLC, "", "text/plain", new byte[0], null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Empty conversationId rejected at construction", threw);
    }

    private static void testEmptySenderAddressRejected() {
        boolean threw = false;
        try {
            new ChatMessagePayload(MESSAGE_ID, "", HLC, "conv-1", "text/plain", new byte[0], null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Empty senderAddress rejected at construction", threw);
    }

    private static void testNullHlcTimestampRejected() {
        boolean threw = false;
        try {
            new ChatMessagePayload(MESSAGE_ID, SENDER_ADDRESS, null, "conv-1", "text/plain", new byte[0], null);
        } catch (NullPointerException e) {
            threw = true;
        }
        check("Null hlcTimestamp rejected at construction", threw);
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + description);
        } else {
            failed++;
            System.out.println("[FAIL] " + description);
        }
    }
}
