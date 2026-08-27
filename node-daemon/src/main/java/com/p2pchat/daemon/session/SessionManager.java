package com.p2pchat.daemon.session;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.SecureSessionService;
import com.p2pchat.daemon.dispatch.ApplicationMessageRouter;
import com.p2pchat.daemon.dispatch.DispatchedMessage;
import com.p2pchat.daemon.send.OutboundMessageService;
import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.HybridLogicalClock;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.messaging.wire.ChatWireMessage;
import com.p2pchat.messaging.wire.DeliveryReceiptPayload;
import com.p2pchat.messaging.wire.ReadReceiptPayload;
import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.ConnectionStrategy;
import com.p2pchat.network.ConnectivityStatus;
import com.p2pchat.network.DialableAddressResolver;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.ConversationType;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;

import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.SignalProtocolStore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M6e-2: the actual heart of M6 — the long-running, multi-session core that M6a (dispatch), M6b
 * (outbound send path), and M6e-1 (persistent session store) were all built to be wired into.
 * Everything through M5e is one-shot, single-peer, hardcoded-remote demo Mains; this replaces
 * that shape with a real multi-peer daemon core, callable concurrently from any number of remote
 * peers on one listener.
 *
 * <p><b>Scope, stated explicitly — what this does and does not cover:</b>
 * <ul>
 *   <li><b>In scope:</b> the full inbound pipeline (decrypt → {@link ApplicationMessageRouter}
 *   dispatch → persist, with real dedup and storage-transaction boundaries) for chat traffic,
 *   and outbound chat sends wrapping {@link OutboundMessageService}. Both are fully buildable
 *   now — PQXDH session establishment happens transparently on the receiving side of a PreKey
 *   message (real Signal Protocol behavior: the responder derives the session from their own
 *   already-held prekey material plus what's embedded in the incoming message, no bundle needed
 *   on that side — confirmed by re-reading {@code LibsignalSecureSessionService}'s real,
 *   unmodified contract before assuming it, not taken on faith), so nothing here depends on
 *   discovery (M6f) or an RPC surface (M6g) existing yet.</li>
 *   <li><b>Explicitly deferred, not silently skipped:</b> relay-delivered <i>inbound</i>
 *   reception. {@code start()} below registers only {@code OnEnvelopeMessage}, not a {@code
 *   RelayEventHandler} — nothing through M5e or M6b ever proved receiving a relay-forwarded
 *   message either, so this would be genuinely new networking capability, not just wiring
 *   already-proven pieces together. Named here so it's a tracked gap, not a silent one.</li>
 *   <li><b>Explicitly deferred, not silently skipped:</b> the full resumable file-transfer
 *   chunk state machine {@code FileReceiverMain}/{@code FileSenderMain} (M4c/M4d) already
 *   proved. File-transfer messages are correctly routed here — proving M6a's dispatcher earns
 *   its keep for both message families, not just chat — but handling is delegated to a
 *   pluggable {@link FileTransferHandler}, not reimplemented inline.</li>
 *   <li><b>Explicitly deferred, not silently skipped:</b> pre-key bundle lifecycle/replenishment
 *   policy. Already named as undecided when M6 was first scoped ("the persistence mechanism now
 *   exists — M6e-1 — but the generation policy is still undecided"); still true, still not this
 *   milestone's job, which is wiring the store into a daemon core, not managing its contents.</li>
 * </ul>
 *
 * <p><b>Canonical identity, applied correctly this time.</b> Every {@link SignalProtocolAddress}
 * here is built from the {@link PeerId} {@code OnEnvelopeMessage} itself delivers — confirmed,
 * by reading every construction site in {@code core-network} (M3d's own unification), that this
 * is always the real libp2p base58 peer ID, since the network layer has no dependency on
 * core-identity and could never construct anything else. Deliberately not {@code
 * identity.peerId()} (the app-identity hex ID) — the exact mismatch flagged as a real risk when
 * M6 was first scoped, and never actually made until now that a session manager exists to make
 * it in. Device id is {@code 1}, not {@code DeviceId.DEFAULT}'s {@code "0"} — checked against
 * every real {@code SignalProtocolAddress} construction site in this project (20+, zero
 * exceptions) before writing this, not assumed by analogy to the unrelated storage-layer type.
 *
 * <p><b>No per-peer {@code LibsignalSecureSessionService} map.</b> Its real methods —
 * {@code establishSession}/{@code encrypt}/{@code decrypt} — all take the remote {@code
 * SignalProtocolAddress} as a parameter rather than baking it into the object (confirmed by
 * re-reading that class before assuming otherwise), so ONE instance, constructed once this
 * node's own peer ID is known, correctly serves every remote peer — the store itself is what's
 * keyed per-address, not this service.
 *
 * <p><b>{@code signalStore} MUST already be {@code SynchronizedSignalProtocolStore}-wrapped by
 * the caller</b> — not enforced by the type system ({@link SignalProtocolStore} is the
 * parameter type, same as {@code LibsignalSecureSessionService} itself accepts), but genuinely
 * required: {@link #handleInboundEnvelope} and {@link #sendChatMessage} can run concurrently
 * (an inbound message from one peer arriving while an outbound send to another is in flight),
 * both touching the same store. This is also what correctly handles the case of two peers
 * simultaneously initiating sessions with each other — each processes the other's incoming
 * PreKey message via libsignal's own session-record handling (which already tracks a current
 * chain plus prior ones specifically for this race, deterministically converging as messages
 * decrypt against whichever state actually matches) while their own outbound establishment
 * proceeds — {@code SessionManager} adds no custom logic for this because none is needed, only
 * because it never bypasses normal establish/encrypt/decrypt calls; the store's own thread
 * safety is what keeps two concurrent callers from corrupting each other's writes to it.
 *
 * <p><b>A single-threaded inbound executor, deliberately</b> — not a small pool. Two reasons,
 * not one: the same "correctness over throughput, human-paced messaging has no realistic
 * contention problem" reasoning {@link OutboundMessageService} already established for its own
 * pool: and, concretely here, it removes a real race a pool would have — two near-simultaneous
 * deliveries of the same {@code messageId} both passing a dedup check before either's insert
 * commits, the second then failing on {@code messages}' own primary key. Strict sequential
 * processing means no two messages' check-then-insert can ever interleave, without needing to
 * verify or trust {@code StorageService}'s thread-safety under genuinely concurrent access,
 * which nothing in this project currently establishes either way.
 *
 * <p><b>A daemon does not crash on one peer's malformed input.</b> Every demo Main through M5e
 * could reasonably let an uncaught exception end the process — it was proving one thing, once.
 * This isn't: one peer sending a corrupted frame must not take down every other peer's session.
 * {@link #handleInboundEnvelope} catches broadly and logs, deliberately, where a demo would
 * have been fine letting it propagate.
 */
public final class SessionManager implements AutoCloseable {

    private final PeerNetworkService network;
    private final StorageService storage;
    private final SignalProtocolStore signalStore;
    private final FileTransferHandler fileTransferHandler;
    private final ConnectionStrategy connectionStrategy;
    private final OutboundMessageService outbound;
    private final ExecutorService inboundExecutor;

    // Set once, at the end of start() -- see class Javadoc for why these can't be constructed
    // any earlier: the local libp2p peer id (and therefore the local SignalProtocolAddress and
    // this node's own HLC node id) isn't known until network.start() has actually returned.
    private volatile PeerId ownPeerId;
    private volatile String ownAddress;
    private volatile HybridLogicalClock clock;
    private volatile SecureSessionService sessions;

    public SessionManager(PeerNetworkService network, StorageService storage, SignalProtocolStore signalStore,
                           FileTransferHandler fileTransferHandler) {
        this.network = network;
        this.storage = storage;
        this.signalStore = signalStore;
        this.fileTransferHandler = fileTransferHandler;
        this.connectionStrategy = new ConnectionStrategy(network, 5_000);
        this.outbound = new OutboundMessageService(connectionStrategy, Duration.ofSeconds(15));
        this.inboundExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Starts the network listener and finishes wiring this node's own identity-dependent state.
     * Blocks until the network is up (matching {@code PeerNetworkService.start(...)}'s own
     * convention), but everything after that — decrypting, dispatching, persisting — always
     * happens off this call's thread, on {@link #inboundExecutor}.
     */
    public void start(int listenPort, byte[] identityKeySeed) {
        network.start(listenPort, identityKeySeed, this::handleInboundEnvelope);

        this.ownAddress = DialableAddressResolver.resolve(network.listenAddresses());
        String ownPeerIdValue = extractPeerId(ownAddress);
        this.ownPeerId = PeerId.of(ownPeerIdValue);
        this.clock = new HybridLogicalClock(ownPeerIdValue);
        this.sessions = new LibsignalSecureSessionService(signalStore, new SignalProtocolAddress(ownPeerIdValue, 1));
    }

    public PeerId localPeerId() {
        requireStarted();
        return ownPeerId;
    }

    public boolean hasSession(PeerId peerId) {
        requireStarted();
        return signalStore.containsSession(new SignalProtocolAddress(peerId.value(), 1));
    }

    /**
     * Sends a chat message to {@code targetPeerId}. {@code bundleIfNoSessionYet} is required for
     * genuine first contact with a peer — with no discovery (M6f) built yet, the caller supplies
     * it explicitly, same as every M5c/M5d demo's {@code -Pbundlefile} already required. Safe to
     * pass even when a session already exists — {@link #hasSession} is checked internally, so a
     * bundle supplied "just in case" is never used to redundantly (and incorrectly) re-establish
     * an existing session.
     *
     * @return a future that always resolves to a {@link ConnectivityStatus}, never completes
     *         exceptionally — extending {@link OutboundMessageService}'s own stated guarantee to
     *         cover the crypto/storage steps this method adds in front of it, not just the send.
     */
    public CompletableFuture<ConnectivityStatus> sendChatMessage(PeerId targetPeerId, String directMultiaddr,
                                                                  String relayMultiaddr, PreKeyBundle bundleIfNoSessionYet,
                                                                  String conversationId, String text) {
        requireStarted();
        SignalProtocolAddress remote = new SignalProtocolAddress(targetPeerId.value(), 1);
        String messageId = UUID.randomUUID().toString();
        try {
            if (bundleIfNoSessionYet != null && !signalStore.containsSession(remote)) {
                sessions.establishSession(remote, bundleIfNoSessionYet);
            }
            HlcTimestamp timestamp = clock.now();
            byte[] content = text.getBytes(StandardCharsets.UTF_8);
            ChatMessagePayload payload = new ChatMessagePayload(
                    messageId, ownAddress, timestamp, conversationId, "text/plain", content, null);
            EncryptedFrame frame = sessions.encrypt(remote, ChatMessageCodec.encode(payload));
            byte[] wire = EncryptedFrameCodec.encode(frame);

            storage.runInTransaction(() -> {
                storage.saveConversation(new Conversation(
                        conversationId, ConversationType.DIRECT, targetPeerId.value(), System.currentTimeMillis()));
                storage.saveMessage(new Message(
                        messageId, conversationId, ownPeerId, DeviceId.DEFAULT, timestamp.toString(),
                        "text/plain", content, DeliveryState.SENDING, System.currentTimeMillis()));
                return null;
            });

            return outbound.send(directMultiaddr, relayMultiaddr, targetPeerId.value(), wire)
                    .thenApply(status -> {
                        // The delivery_state a caller sees now actually reflects whether the
                        // send worked, not just that an attempt was recorded.
                        storage.updateDeliveryState(messageId,
                                status == ConnectivityStatus.UNREACHABLE ? DeliveryState.FAILED : DeliveryState.SENT);
                        return status;
                    });
        } catch (Exception e) {
            // Same principle as ConnectionStrategy/OutboundMessageService: a failure this early
            // (session establishment, encryption) still resolves to a definitive status rather
            // than throwing out of this method.
            return CompletableFuture.completedFuture(ConnectivityStatus.UNREACHABLE);
        }
    }

    private void handleInboundEnvelope(PeerId sender, byte[] wireData) {
        inboundExecutor.submit(() -> {
            try {
                EncryptedFrame frame = EncryptedFrameCodec.decode(wireData);
                SignalProtocolAddress remote = new SignalProtocolAddress(sender.value(), 1);
                byte[] plaintext = sessions.decrypt(remote, frame);
                handleDecryptedPlaintext(sender, plaintext);
            } catch (Exception e) {
                // One peer's malformed/corrupted frame must not take the daemon down, and must
                // not stop other peers' messages already queued on inboundExecutor from being
                // served.
                System.err.println("[session-manager] failed to process inbound envelope from " + sender + ": " + e);
            }
        });
    }

    /**
     * The decrypt-independent seam: given already-decrypted plaintext, dispatch and persist.
     * Package-private deliberately — this is what {@code SessionManagerReceivePipelineTest}
     * calls directly, real execution against a real {@code StorageService}, needing neither
     * jvm-libp2p nor libsignal-client to verify the dispatch/dedup/persistence logic actually
     * works — the same testable-seam approach M6b (fake network) and M6e-1 (real SQLite, fake
     * crypto) already established.
     */
    void handleDecryptedPlaintext(PeerId sender, byte[] plaintext) {
        DispatchedMessage dispatched = ApplicationMessageRouter.dispatch(plaintext);
        switch (dispatched) {
            case DispatchedMessage.Chat chat -> handleChatMessage(sender, chat.message());
            case DispatchedMessage.FileTransfer file -> handleFileTransferMessage(sender, file.message());
        }
    }

    private void handleChatMessage(PeerId sender, ChatWireMessage message) {
        switch (message) {
            case ChatMessagePayload chat -> handleChatMessagePayload(sender, chat);
            case DeliveryReceiptPayload receipt ->
                    storage.updateDeliveryState(receipt.messageId(), DeliveryState.DELIVERED);
            case ReadReceiptPayload read -> storage.markMessagesReadUpTo(
                    read.conversationId(), ownPeerId, read.readUpToHlcTimestamp().toString());
        }
    }

    private void handleChatMessagePayload(PeerId sender, ChatMessagePayload chat) {
        // Regression fix: the pre-M6 cleanup pass added this exact checkDrift-before-update gate
        // to ChatListenerMain/ChatSenderMain (see README's M5e section), on the reasoning that an
        // implausible remote timestamp should reject the message outright -- not persisted, not
        // acknowledged, clock not advanced -- rather than let it corrupt this node's own clock or
        // sit in message history with a bogus HLC order. SessionManager (M6e-2), which superseded
        // those demo Mains as the real daemon core, never picked the gate up: the block this
        // replaces called clock.update() inside a catch for RemoteTimestampRejectedException, but
        // update() can never throw that -- only checkDrift() does -- so that catch was silently
        // dead code and every remote timestamp was accepted unconditionally. Fixed by calling
        // checkDrift() first, before dedup and before persistence, matching ChatListenerMain's own
        // ordering exactly.
        try {
            clock.checkDrift(chat.hlcTimestamp());
        } catch (HybridLogicalClock.RemoteTimestampRejectedException e) {
            System.err.println("[session-manager] REJECTED message " + chat.messageId() + " from " + sender
                    + " -- clock drift too large: " + e.getMessage());
            return;
        }

        SignalProtocolAddress remote = new SignalProtocolAddress(sender.value(), 1);
        String conversationId = deriveDirectConversationId(ownPeerId.value(), sender.value());

        // M5d dedup, consolidated here rather than duplicated per demo Main: architecture-spec
        // §15's "duplicate message delivery... -> dedup on message_id at the storage layer
        // before it ever reaches the UI." A duplicate is still acknowledged -- the sender's
        // earlier ack may have been lost -- but not re-persisted (would violate the messages
        // primary key anyway). Safe against the check-then-insert race precisely because
        // inboundExecutor is single-threaded -- see class Javadoc.
        boolean isDuplicate = storage.hasMessage(chat.messageId());

        // checkDrift() has already gated untrusted input above, so update() itself can never
        // throw RemoteTimestampRejectedException here -- safe to call unconditionally.
        clock.update(chat.hlcTimestamp());

        if (!isDuplicate) {
            // The storage-transaction-boundaries decision from the original M6 open-decisions
            // list, resolved here: the whole upsert-conversation + save-message sequence commits
            // or rolls back as one unit, not two independent writes a crash between them could
            // leave half-applied.
            storage.runInTransaction(() -> {
                storage.saveConversation(new Conversation(
                        conversationId, ConversationType.DIRECT, sender.value(), System.currentTimeMillis()));
                storage.saveMessage(new Message(
                        chat.messageId(), conversationId, sender, DeviceId.DEFAULT,
                        chat.hlcTimestamp().toString(), // the ORIGINAL author's timestamp, not clock.update()'s return value
                        chat.contentType(), chat.content(), DeliveryState.DELIVERED, System.currentTimeMillis()));
                return null;
            });
        }

        // Delivery receipt, always -- for a genuine new message and for a re-acked duplicate
        // alike, matching ChatListenerMain's own established reasoning. Deliberately NOT an
        // auto-generated chat reply the way the M5c/M5d demos sent one -- that proved the round
        // trip for a one-shot demo; a real daemon has no business inventing message content on
        // a peer's behalf.
        try {
            byte[] receiptWire = ChatMessageCodec.encode(new DeliveryReceiptPayload(conversationId, chat.messageId()));
            EncryptedFrame receiptFrame = sessions.encrypt(remote, receiptWire);
            outbound.send(chat.senderAddress(), null, sender.value(), EncryptedFrameCodec.encode(receiptFrame));
        } catch (Exception e) {
            System.err.println("[session-manager] failed to send delivery receipt to " + sender + ": " + e);
        }
    }

    private void handleFileTransferMessage(PeerId sender, com.p2pchat.filetransfer.wire.FileTransferMessage message) {
        switch (message) {
            case FileOfferPayload offer -> fileTransferHandler.onFileOffer(sender, offer);
            case FileChunkRequestPayload request -> fileTransferHandler.onFileChunkRequest(sender, request);
            case FileChunkPayload chunk -> fileTransferHandler.onFileChunk(sender, chunk);
        }
    }

    private void requireStarted() {
        if (ownPeerId == null) {
            throw new IllegalStateException("SessionManager.start(...) must be called before use");
        }
    }

    /**
     * Package-private, tests only: sets the identity-dependent state {@link #start} normally
     * derives from a real network listener, without needing one. What makes {@link
     * #handleDecryptedPlaintext} genuinely testable without jvm-libp2p or libsignal-client —
     * everything it touches ({@code storage}, {@code clock}) can be real; only {@code sessions}
     * needs a fake, since the auto-delivery-receipt path calls {@code sessions.encrypt(...)}.
     */
    void initializeForTesting(PeerId ownPeerId, String ownAddress, HybridLogicalClock clock,
                               SecureSessionService sessions) {
        this.ownPeerId = ownPeerId;
        this.ownAddress = ownAddress;
        this.clock = clock;
        this.sessions = sessions;
    }

    // Same logic as ChatListenerMain's own private helpers (M5c) -- not extracted to a shared
    // location and reused from there. Touching that file at all, even a safe-looking delegation
    // edit, was judged not worth risking its "untouched, proven regression tool" status for two
    // small functions -- see the README's own "Principle, not a task" note on this exact point.

    private static String extractPeerId(String multiaddr) {
        int index = multiaddr.lastIndexOf("/p2p/");
        if (index == -1) {
            throw new IllegalArgumentException("Address does not contain a /p2p/<peer-id> component: " + multiaddr);
        }
        return multiaddr.substring(index + "/p2p/".length());
    }

    private static String deriveDirectConversationId(String peerIdA, String peerIdB) {
        return peerIdA.compareTo(peerIdB) <= 0
                ? "direct-" + peerIdA + "-" + peerIdB
                : "direct-" + peerIdB + "-" + peerIdA;
    }

    @Override
    public void close() {
        inboundExecutor.shutdown();
        outbound.close();
        try {
            network.stop();
        } catch (Exception e) {
            System.err.println("[session-manager] error stopping network: " + e);
        }
    }
}
