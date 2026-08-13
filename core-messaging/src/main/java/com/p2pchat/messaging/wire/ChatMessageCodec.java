package com.p2pchat.messaging.wire;

import com.p2pchat.messaging.HlcTimestamp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format, matching {@code core-filetransfer.wire.FileTransferMessageCodec}'s convention
 * exactly (marker byte + length-prefixed UTF-8 strings + fixed-size fields):
 *
 * <pre>
 * CHAT_MESSAGE (marker 2):      [messageId][senderAddress][hlcTimestamp][conversationId][contentType]
 *                                [4 bytes content length][content bytes]
 *                                [1 byte hasReplyTo][replyToMessageId if hasReplyTo=1]
 * DELIVERY_RECEIPT (marker 3):  [conversationId][messageId]
 * READ_RECEIPT (marker 4):      [conversationId][readUpToHlcTimestamp]
 * </pre>
 *
 * where every {@code [string]} above is {@code [4-byte length][UTF-8 bytes]}, and
 * {@code [hlcTimestamp]}/{@code [readUpToHlcTimestamp]} are the same string form encoded via
 * {@link HlcTimestamp#toString()}, decoded via {@link HlcTimestamp#parse}, not a separately
 * invented binary layout — M5a's round trip is already proven, so M5b builds on it rather than
 * duplicating it.
 *
 * <p>Marker values reuse docs/architecture-spec.md §6's {@code EnvelopeType} numbering exactly
 * ({@code CHAT_MESSAGE=2}, {@code DELIVERY_RECEIPT=3}, {@code READ_RECEIPT=4}), matching M4b's
 * own precedent of reusing that numbering rather than inventing a parallel one.
 *
 * <p>Logic verified standalone before being wired into any networking — see the M5b section of
 * README.md.
 */
public final class ChatMessageCodec {

    private static final byte CHAT_MESSAGE_MARKER = 2;
    private static final byte DELIVERY_RECEIPT_MARKER = 3;
    private static final byte READ_RECEIPT_MARKER = 4;

    private static final byte NO_REPLY = 0;
    private static final byte HAS_REPLY = 1;

    private ChatMessageCodec() {
    }

    public static byte[] encode(ChatWireMessage message) {
        return switch (message) {
            case ChatMessagePayload chat -> encodeChatMessage(chat);
            case DeliveryReceiptPayload delivery -> encodeDeliveryReceipt(delivery);
            case ReadReceiptPayload read -> encodeReadReceipt(read);
        };
    }

    public static ChatWireMessage decode(byte[] wire) {
        ByteBuffer buf = ByteBuffer.wrap(wire);
        byte marker = buf.get();
        return switch (marker) {
            case CHAT_MESSAGE_MARKER -> decodeChatMessage(buf);
            case DELIVERY_RECEIPT_MARKER -> decodeDeliveryReceipt(buf);
            case READ_RECEIPT_MARKER -> decodeReadReceipt(buf);
            default -> throw new IllegalArgumentException("Unknown chat message marker: " + marker);
        };
    }

    private static byte[] encodeChatMessage(ChatMessagePayload chat) {
        byte[] messageIdBytes = utf8(chat.messageId());
        byte[] senderAddressBytes = utf8(chat.senderAddress());
        byte[] hlcBytes = utf8(chat.hlcTimestamp().toString());
        byte[] conversationIdBytes = utf8(chat.conversationId());
        byte[] contentTypeBytes = utf8(chat.contentType());
        byte[] content = chat.content();
        boolean hasReply = chat.replyToMessageId() != null;
        byte[] replyToBytes = hasReply ? utf8(chat.replyToMessageId()) : new byte[0];

        int size = 1
                + 4 + messageIdBytes.length
                + 4 + senderAddressBytes.length
                + 4 + hlcBytes.length
                + 4 + conversationIdBytes.length
                + 4 + contentTypeBytes.length
                + 4 + content.length
                + 1
                + (hasReply ? 4 + replyToBytes.length : 0);

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(CHAT_MESSAGE_MARKER);
        putBytes(buf, messageIdBytes);
        putBytes(buf, senderAddressBytes);
        putBytes(buf, hlcBytes);
        putBytes(buf, conversationIdBytes);
        putBytes(buf, contentTypeBytes);
        putBytes(buf, content);
        buf.put(hasReply ? HAS_REPLY : NO_REPLY);
        if (hasReply) {
            putBytes(buf, replyToBytes);
        }
        return buf.array();
    }

    private static ChatMessagePayload decodeChatMessage(ByteBuffer buf) {
        String messageId = getString(buf);
        String senderAddress = getString(buf);
        HlcTimestamp hlcTimestamp = HlcTimestamp.parse(getString(buf));
        String conversationId = getString(buf);
        String contentType = getString(buf);
        byte[] content = getBytes(buf);
        byte replyFlag = buf.get();
        String replyToMessageId = (replyFlag == HAS_REPLY) ? getString(buf) : null;
        return new ChatMessagePayload(messageId, senderAddress, hlcTimestamp, conversationId, contentType, content, replyToMessageId);
    }

    private static byte[] encodeDeliveryReceipt(DeliveryReceiptPayload delivery) {
        byte[] conversationIdBytes = utf8(delivery.conversationId());
        byte[] messageIdBytes = utf8(delivery.messageId());

        int size = 1 + 4 + conversationIdBytes.length + 4 + messageIdBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(DELIVERY_RECEIPT_MARKER);
        putBytes(buf, conversationIdBytes);
        putBytes(buf, messageIdBytes);
        return buf.array();
    }

    private static DeliveryReceiptPayload decodeDeliveryReceipt(ByteBuffer buf) {
        String conversationId = getString(buf);
        String messageId = getString(buf);
        return new DeliveryReceiptPayload(conversationId, messageId);
    }

    private static byte[] encodeReadReceipt(ReadReceiptPayload read) {
        byte[] conversationIdBytes = utf8(read.conversationId());
        byte[] hlcBytes = utf8(read.readUpToHlcTimestamp().toString());

        int size = 1 + 4 + conversationIdBytes.length + 4 + hlcBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(READ_RECEIPT_MARKER);
        putBytes(buf, conversationIdBytes);
        putBytes(buf, hlcBytes);
        return buf.array();
    }

    private static ReadReceiptPayload decodeReadReceipt(ByteBuffer buf) {
        String conversationId = getString(buf);
        HlcTimestamp readUpToHlcTimestamp = HlcTimestamp.parse(getString(buf));
        return new ReadReceiptPayload(conversationId, readUpToHlcTimestamp);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putBytes(ByteBuffer buf, byte[] bytes) {
        buf.putInt(bytes.length);
        buf.put(bytes);
    }

    private static byte[] getBytes(ByteBuffer buf) {
        int length = buf.getInt();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return bytes;
    }

    private static String getString(ByteBuffer buf) {
        return new String(getBytes(buf), StandardCharsets.UTF_8);
    }
}
