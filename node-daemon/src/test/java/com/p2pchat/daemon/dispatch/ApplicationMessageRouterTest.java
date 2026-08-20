package com.p2pchat.daemon.dispatch;

import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessageCodec;
import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.messaging.wire.DeliveryReceiptPayload;
import com.p2pchat.messaging.wire.ReadReceiptPayload;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6a. Each "real message" test does a full round trip through the *actual* codecs — encode via
 * {@code ChatMessageCodec}/{@code FileTransferMessageCodec} exactly as {@code
 * SecureSessionService.encrypt(...)}'s caller would, then dispatch, then assert both which
 * {@code DispatchedMessage} variant came back and that the payload survived intact. This never
 * duplicates either codec's own encode/decode logic (that's {@code ChatMessageCodecTest}/{@code
 * FileTransferMessageCodecTest}'s job) — it only proves routing picked the right one.
 */
class ApplicationMessageRouterTest {

    @Nested
    class ChatMarkers {

        @Test
        void routesChatMessageToChat() {
            ChatMessagePayload original = new ChatMessagePayload(
                    "a1a1a1a1-0000-4000-8000-000000000001", "/ip4/127.0.0.1/tcp/9200/p2p/12D3KooWSender",
                    new HlcTimestamp(1_000L, 0, "12D3KooWSender"),
                    "direct-12D3KooWA-12D3KooWB", "text/plain",
                    "hello".getBytes(StandardCharsets.UTF_8), null);

            DispatchedMessage dispatched = ApplicationMessageRouter.dispatch(ChatMessageCodec.encode(original));

            assertThat(dispatched).isInstanceOf(DispatchedMessage.Chat.class);
            // Not isEqualTo(original) -- ChatMessagePayload has a byte[] content field, and a
            // record's generated equals() compares arrays by reference, not content, so a fresh
            // array from decode() would never be equal to the original even when correct. Caught
            // by actually running this test, not by hand-tracing it.
            ChatMessagePayload decoded = (ChatMessagePayload) ((DispatchedMessage.Chat) dispatched).message();
            assertThat(decoded.messageId()).isEqualTo(original.messageId());
            assertThat(decoded.senderAddress()).isEqualTo(original.senderAddress());
            assertThat(decoded.hlcTimestamp()).isEqualTo(original.hlcTimestamp());
            assertThat(decoded.conversationId()).isEqualTo(original.conversationId());
            assertThat(decoded.contentType()).isEqualTo(original.contentType());
            assertThat(decoded.content()).isEqualTo(original.content());
            assertThat(decoded.replyToMessageId()).isEqualTo(original.replyToMessageId());
        }

        @Test
        void routesDeliveryReceiptToChat() {
            DeliveryReceiptPayload original = new DeliveryReceiptPayload(
                    "direct-a-b", "a1a1a1a1-0000-4000-8000-000000000001");

            DispatchedMessage dispatched = ApplicationMessageRouter.dispatch(ChatMessageCodec.encode(original));

            assertThat(dispatched).isInstanceOf(DispatchedMessage.Chat.class);
            assertThat(((DispatchedMessage.Chat) dispatched).message()).isEqualTo(original);
        }

        @Test
        void routesReadReceiptToChat() {
            ReadReceiptPayload original = new ReadReceiptPayload("direct-a-b", new HlcTimestamp(2_000L, 1, "node"));

            DispatchedMessage dispatched = ApplicationMessageRouter.dispatch(ChatMessageCodec.encode(original));

            assertThat(dispatched).isInstanceOf(DispatchedMessage.Chat.class);
            assertThat(((DispatchedMessage.Chat) dispatched).message()).isEqualTo(original);
        }
    }

    @Nested
    class FileTransferMarkers {

        @Test
        void routesFileOfferToFileTransfer() {
            FileOfferPayload original = new FileOfferPayload(
                    "transfer-1", "/ip4/127.0.0.1/tcp/9100/p2p/12D3KooWSender", "photo.png",
                    2048L, "deadbeef", 512, 4, new byte[32]); // codec enforces a 32-byte AES-256 key

            DispatchedMessage dispatched =
                    ApplicationMessageRouter.dispatch(FileTransferMessageCodec.encode(original));

            assertThat(dispatched).isInstanceOf(DispatchedMessage.FileTransfer.class);
            // Same byte[] (fileKey)-breaks-record-equals() gotcha as ChatMessagePayload above.
            FileOfferPayload decoded = (FileOfferPayload) ((DispatchedMessage.FileTransfer) dispatched).message();
            assertThat(decoded.transferId()).isEqualTo(original.transferId());
            assertThat(decoded.senderAddress()).isEqualTo(original.senderAddress());
            assertThat(decoded.fileName()).isEqualTo(original.fileName());
            assertThat(decoded.fileSize()).isEqualTo(original.fileSize());
            assertThat(decoded.fileHash()).isEqualTo(original.fileHash());
            assertThat(decoded.chunkSize()).isEqualTo(original.chunkSize());
            assertThat(decoded.totalChunks()).isEqualTo(original.totalChunks());
            assertThat(decoded.fileKey()).isEqualTo(original.fileKey());
        }

        @Test
        void routesFileChunkRequestToFileTransfer() {
            FileChunkRequestPayload original = new FileChunkRequestPayload("transfer-1", new int[]{0, 2, 3});

            DispatchedMessage dispatched =
                    ApplicationMessageRouter.dispatch(FileTransferMessageCodec.encode(original));

            assertThat(dispatched).isInstanceOf(DispatchedMessage.FileTransfer.class);
            // FileChunkRequestPayload's int[] field breaks record equals() (array identity, not
            // content) — same reason its own codec test compares fields, not the record itself.
            FileChunkRequestPayload decoded =
                    (FileChunkRequestPayload) ((DispatchedMessage.FileTransfer) dispatched).message();
            assertThat(decoded.transferId()).isEqualTo(original.transferId());
            assertThat(decoded.missingChunkIndices()).isEqualTo(original.missingChunkIndices());
        }

        @Test
        void routesFileChunkToFileTransfer() {
            FileChunkPayload original = new FileChunkPayload(
                    "transfer-1", 2, new byte[12], new byte[]{5, 6, 7, 8}); // codec enforces a 12-byte GCM nonce

            DispatchedMessage dispatched =
                    ApplicationMessageRouter.dispatch(FileTransferMessageCodec.encode(original));

            assertThat(dispatched).isInstanceOf(DispatchedMessage.FileTransfer.class);
            // Same array-equals caveat as FileChunkRequestPayload above.
            FileChunkPayload decoded = (FileChunkPayload) ((DispatchedMessage.FileTransfer) dispatched).message();
            assertThat(decoded.transferId()).isEqualTo(original.transferId());
            assertThat(decoded.chunkIndex()).isEqualTo(original.chunkIndex());
            assertThat(decoded.nonce()).isEqualTo(original.nonce());
            assertThat(decoded.ciphertext()).isEqualTo(original.ciphertext());
        }
    }

    @Nested
    class RejectedInput {

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void rejectsEmptyArray() {
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @ParameterizedTest
        @ValueSource(bytes = {0, 1})
        void rejectsHandshakeMarkersAsReachingRouterUnexpectedly(byte marker) {
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(new byte[]{marker, 0, 0, 0}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HANDSHAKE");
        }

        @Test
        void rejectsGroupOpAsNotYetImplemented() {
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(new byte[]{5, 0, 0, 0}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GROUP_OP")
                    .hasMessageContaining("M8");
        }

        @Test
        void rejectsPresencePingAsNotYetImplemented() {
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(new byte[]{9, 0, 0, 0}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PRESENCE_PING");
        }

        @ParameterizedTest
        @ValueSource(bytes = {10, 42, -1, -128})
        void rejectsTrulyUnknownMarkers(byte marker) {
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(new byte[]{marker, 0, 0, 0}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown application message marker");
        }

        @Test
        void propagatesCodecsOwnValidationForTruncatedChatMarker() {
            // Marker says CHAT_MESSAGE (2) but there's nothing else — ChatMessageCodec.decode's
            // own length-prefix validation should reject this, not the router silently
            // succeeding with garbage. Proves the router doesn't swallow or mask codec-level
            // malformed-input errors.
            assertThatThrownBy(() -> ApplicationMessageRouter.dispatch(new byte[]{2}))
                    .isInstanceOf(Exception.class);
        }
    }
}
