package com.p2pchat.daemon.session;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.HybridLogicalClock;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.RelayFrame;
import com.p2pchat.network.RelayFrameType;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-2 (pre-m6h-hardening-plan.md, Track A): {@link SessionManager#handleRelayDeliveredFrame} is
 * the seam that makes a relay-delivered message actually reach this class's decrypt/dispatch
 * pipeline — the gap A-1 named and deliberately left open. Same "real storage/clock, fake
 * network/crypto" rigor {@link SessionManagerReceivePipelineTest} already established for {@code
 * handleDecryptedPlaintext} — this class covers the one hop before it.
 *
 * <p>The specific thing worth proving in isolation, and the reason this isn't just folded into
 * {@code SessionManagerReceivePipelineTest}: a relayed message must be attributed to {@link
 * RelayFrame#peerId()} (the true original sender), never to the relay's own identity — see
 * {@link SessionManager#handleRelayDeliveredFrame}'s own Javadoc for the hardware-confirmed
 * reason that distinction is real ({@code RelayProtocol.Handler.onMessage} always passes the
 * RELAY's own {@code stream.remotePeerId()} to {@code onFrame}, a completely different identity
 * from who a {@code DELIVER} frame is actually from). There is no "relay identity" parameter
 * anywhere in this test, on purpose: {@link SessionManager#handleRelayDeliveredFrame}'s signature
 * takes only a {@link RelayFrame}, exactly so that confusion is unrepresentable at this seam, not
 * just avoided by convention. This test proves the one identity it does take is used correctly.
 */
class SessionManagerRelayInboundTest {

    private SqliteDatabase database;
    private SqliteStorageService storage;
    private SessionManager sessionManager;
    private FakeNetworkForSessionTest fakeNetwork;

    private final PeerId ownPeerId = PeerId.of("12D3KooWOwnNode");
    private final PeerId senderPeerId = PeerId.of("12D3KooWTrueSender");
    private final String senderAddress = "/ip4/127.0.0.1/tcp/9100/p2p/12D3KooWTrueSender";

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
        fakeNetwork = new FakeNetworkForSessionTest();

        // signalStore is null deliberately, same reasoning as SessionManagerReceivePipelineTest:
        // handleRelayDeliveredFrame -> handleInboundEnvelope never touches it directly, only
        // FakeSecureSessionServiceForTest (injected below) does.
        sessionManager = new SessionManager(fakeNetwork, storage, null, new FileTransferHandler() {
        });
        sessionManager.initializeForTesting(ownPeerId, "/ip4/127.0.0.1/tcp/9200/p2p/" + ownPeerId.value(),
                new HybridLogicalClock(ownPeerId.value()), new FakeSecureSessionServiceForTest());
    }

    @AfterEach
    void tearDown() throws Exception {
        sessionManager.close();
        database.close();
    }

    @Test
    void relayDeliveredChatMessageIsAttributedToTheFramesOriginalSender() throws Exception {
        ChatMessagePayload chat = new ChatMessagePayload(
                "b2b2b2b2-0000-4000-8000-000000000001", senderAddress,
                new HlcTimestamp(1000L, 0, senderPeerId.value()), "direct-relay-a-b", "text/plain",
                "hello via relay".getBytes(StandardCharsets.UTF_8), null);
        byte[] wire = EncryptedFrameCodec.encode(new EncryptedFrame(false, ChatMessageCodec.encode(chat)));

        // frame.peerId() is senderPeerId's value -- the ONLY identity this call has access to,
        // since handleRelayDeliveredFrame's signature takes no separate "who's on the raw
        // stream" parameter at all. If a future edit accidentally threaded one back in and used
        // it instead, this message would end up attributed to the wrong peer and this test would
        // fail -- either on the assertion below, or upstream as a decrypt/session error once this
        // runs against the real LibsignalSecureSessionService instead of the fake.
        RelayFrame deliverFrame = new RelayFrame(RelayFrameType.DELIVER, senderPeerId.value(), wire);

        sessionManager.handleRelayDeliveredFrame(deliverFrame);

        awaitSentCount(1);
        assertThat(storage.hasMessage(chat.messageId())).isTrue();
        // The auto-delivery-receipt going to senderAddress -- not some other address -- is this
        // test's real evidence that sender.value() reaching handleChatMessagePayload downstream
        // was senderPeerId's, not some other identity: handleChatMessagePayload derives its
        // reply target from the sender it was actually called with.
        assertThat(fakeNetwork.sentTo()).containsExactly(senderAddress);
    }

    @Test
    void nonDeliverRelayFramesAreIgnoredSynchronously() {
        // FORWARD is client-to-relay only and should never reach this method in practice, but a
        // defensive no-op is cheap and worth proving: handleRelayDeliveredFrame returns before
        // ever touching handleInboundEnvelope/inboundExecutor for anything that isn't DELIVER, so
        // there is no async work to await here at all -- this assertion runs immediately, not
        // racily.
        RelayFrame forwardFrame = new RelayFrame(RelayFrameType.FORWARD, senderPeerId.value(), new byte[]{1, 2, 3});

        sessionManager.handleRelayDeliveredFrame(forwardFrame);

        assertThat(fakeNetwork.sentTo()).isEmpty();
    }

    private void awaitSentCount(int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (fakeNetwork.sentTo().size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
