package com.p2pchat.daemon;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.PreKeyBundleFactory;
import com.p2pchat.crypto.SecureSessionService;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.messaging.HlcTimestamp;
import com.p2pchat.messaging.HybridLogicalClock;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.ChatMessagePayload;
import com.p2pchat.messaging.wire.ChatWireMessage;
import com.p2pchat.messaging.wire.DeliveryReceiptPayload;
import com.p2pchat.messaging.wire.ReadReceiptPayload;
import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.DialableAddressResolver;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.ConversationType;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M5c: the receiving/replying side of real, bidirectional 1:1 chat over an encrypted connection
 * — the first milestone to actually wire {@code core-messaging} (M5a/M5b) and {@code
 * core-storage}'s {@code saveConversation}/{@code saveMessage} (M4e) into a live send/receive
 * loop. Same "listener also has to reply" shape as {@code FileReceiverMain} (M4c/M4d), but
 * unlike a file transfer's offer/request/chunk asymmetry, a chat exchange is symmetric: this
 * class both persists what it receives AND composes and sends a reply, proving the round trip
 * in both directions within one connection.
 *
 * <p><b>Does not take the sender's address as a startup argument</b> — same reasoning, and the
 * same fix, as {@code FileReceiverMain}: {@link ChatMessagePayload#senderAddress} carries it
 * inside the encrypted, authenticated message itself (added during this milestone — see that
 * record's own Javadoc), so this can safely be the first thing started, with no prior knowledge
 * of who will connect.
 *
 * <p><b>Two real bugs found on the first actual test run, both fixed here — see the M5c section
 * of README.md for the full account:</b>
 * <ul>
 *   <li>{@code network.listenAddresses()[0]} isn't reliably a <i>dialable</i> address — on a
 *   wildcard bind (observed: {@code /ip6/::/tcp/<port>/...} on Windows), it's the "listening on
 *   all interfaces" address itself, which nothing can actually connect <i>to</i>. Originally
 *   fixed here with a private, same-machine-only {@code firstDialableAddress} method; promoted
 *   to {@link DialableAddressResolver} in M5d once a third caller ({@code FileSenderMain}) needed
 *   the identical fix — see that class's own Javadoc for what changed and what didn't.</li>
 *   <li>{@code network.sendEnvelope(...)} was being called synchronously from inside this
 *   {@code OnEnvelopeMessage} callback — which runs on jvm-libp2p/Netty's I/O event loop thread.
 *   {@code sendEnvelope} blocks internally waiting on the new outbound connection's completion,
 *   but that connection's own I/O also needs the event loop thread to make progress — the same
 *   thread the blocking wait is stuck on. Deadlock: the reply dial can never complete because
 *   the thread that would drive it to completion is the one blocked waiting for it. Fixed by
 *   moving the {@code sendEnvelope} call onto a separate thread via {@link CompletableFuture#runAsync}.</li>
 * </ul>
 *
 * <p><b>M5d update — message dedup + delivery/read receipts, all three now handled here:</b>
 * <ul>
 *   <li>Every inbound {@link ChatMessagePayload} is checked against
 *   {@link StorageService#hasMessage} before being persisted — a duplicate delivery (spec §15)
 *   is acknowledged again (idempotent, cheap, and covers the sender's ack from last time having
 *   been lost) but not re-persisted and not re-replied-to.</li>
 *   <li>A non-duplicate message is persisted (unchanged from M5c), then a
 *   {@link DeliveryReceiptPayload} is sent back automatically — delivery is not a user action.
 *   If {@code markRead} is set (see {@code main}'s new optional argument), a
 *   {@link ReadReceiptPayload} is also sent, simulating a real "user opened the conversation"
 *   action that doesn't exist yet without a real UI (M7's territory).</li>
 *   <li>All three outgoing payloads a receive event can now produce — the delivery receipt, the
 *   optional read receipt, and the existing chat reply — are composed first and then sent inside
 *   ONE {@link CompletableFuture#runAsync} block, not three independent ones, so they can't race
 *   each other for the same target connection.</li>
 *   <li>Inbound {@link DeliveryReceiptPayload}/{@link ReadReceiptPayload} (this node's own
 *   previously-sent messages being acknowledged) are handled by
 *   {@link #handleDeliveryReceipt}/{@link #handleReadReceipt} — see {@link StorageService}'s
 *   own M5d Javadoc for the state-transition semantics.</li>
 * </ul>
 *
 * <p><b>A correctness point easy to get backwards, so it's called out explicitly:</b> when a
 * message arrives, {@link HybridLogicalClock#update} is called — but its <i>return value</i> is
 * never what gets persisted as the message's own {@code hlc_timestamp}. That return value is
 * this node's own new local timestamp for the <i>receive event</i> — a distinct causal event
 * from the message itself, used only to correctly advance this node's clock for whatever it
 * timestamps next (the reply, below). The message's own {@code hlc_timestamp}, for storage and
 * ordering, is {@link ChatMessagePayload#hlcTimestamp()} exactly as the sender authored it —
 * unchanged. Storing the local receive-event timestamp instead would mean the same message
 * sorts differently in the sender's own history than in the receiver's, which defeats the
 * entire point of a shared causal clock.
 *
 * <p><b>Pre-M6 cleanup pass — remote clock-drift guard.</b> Every inbound {@link HlcTimestamp} is
 * now checked via {@link HybridLogicalClock#checkDrift} before {@link HybridLogicalClock#update}
 * ever sees it — closes the gap {@code HybridLogicalClock}'s own Javadoc flagged as deferred
 * since M5a ("a malicious or buggy peer could send a physical value far in the future to try to
 * inflate everyone's clock... left for whichever of M5b/M5c first has update fed an untrusted
 * remote value"). M5c/M5d both fed it untrusted values without ever adding the guard; added here.
 */
public class ChatListenerMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9200;
        String replyText = args.length > 1 ? args[1] : "Hello back \u2014 this is an M5c automatic reply.";
        boolean markRead = args.length > 2 && Boolean.parseBoolean(args[2]);

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));

        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        SignalIdentity signalIdentity = SignalIdentityVault.loadOrCreate(baseDir);
        InMemorySignalProtocolStore signalStore =
                new InMemorySignalProtocolStore(signalIdentity.keyPair(), signalIdentity.registrationId());
        SignalProtocolAddress localSignalAddress = new SignalProtocolAddress(identity.peerId(), 1);
        SecureSessionService sessions = new LibsignalSecureSessionService(signalStore, localSignalAddress);

        PreKeyBundle bundle = PreKeyBundleFactory.create(signalStore);
        String bundleBase64 = Base64.getEncoder().encodeToString(PreKeyBundleCodec.encode(bundle));
        Path bundleFile = baseDir.resolve("published-bundle.b64");
        Files.writeString(bundleFile, bundleBase64);

        SqliteDatabase database = SqliteDatabase.openOrCreate(baseDir);
        StorageService storage = new SqliteStorageService(database);

        // One clock for this whole process, shared across every send and receive — exactly the
        // "one clock per node" usage HybridLogicalClock's own Javadoc describes, its first real
        // caller.
        HybridLogicalClock clock = new HybridLogicalClock(identity.peerId());

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            try {
                SignalProtocolAddress remote = new SignalProtocolAddress(sender.value(), 1);

                EncryptedFrame frame = EncryptedFrameCodec.decode(data);
                byte[] plaintext = sessions.decrypt(remote, frame);
                ChatWireMessage message = ChatMessageCodec.decode(plaintext);

                if (message instanceof ChatMessagePayload incoming) {
                    handleChatMessage(incoming, sender, remote, network, sessions, storage, clock, replyText, markRead);
                } else if (message instanceof DeliveryReceiptPayload receipt) {
                    handleDeliveryReceipt(receipt, sender, storage);
                } else if (message instanceof ReadReceiptPayload receipt) {
                    handleReadReceipt(receipt, sender, network, storage);
                } else {
                    // ChatWireMessage is sealed over exactly these three types (see its own
                    // Javadoc) — this branch is unreachable today, kept only as the same
                    // defensive default FileReceiverMain's "unexpected message type" branch uses.
                    System.out.println("[chat] unexpected message type from " + sender + ": " + message);
                }
            } catch (Exception e) {
                System.out.println("[chat] FAILED to process message from " + sender + ": " + e);
            }
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Data dir     : " + baseDir);
        System.out.println("Mark-read    : " + (markRead ? "ON (a ReadReceiptPayload is sent back automatically)" : "OFF (delivery receipts only \u2014 pass true as the 3rd arg / -Pmarkread=true to enable)"));
        System.out.println();
        System.out.println("Give the sender BOTH of these:");
        System.out.println();
        System.out.println("1) Network address:");
        for (String addr : network.listenAddresses()) {
            System.out.println("   " + addr);
        }
        System.out.println();
        System.out.println("2) Pre-key bundle file (changes every restart - always use the latest):");
        System.out.println("   " + bundleFile);
        System.out.println();
        System.out.println("That's everything the sender needs. This listener does not need to know the");
        System.out.println("sender's address in advance - the sender reports its own address inside the");
        System.out.println("encrypted message, so this can safely be the very first thing you start.");
        System.out.println();
        System.out.println("Any message received will be persisted (conversation + message, via");
        System.out.println("core-storage), auto-acked with a delivery receipt, and automatically replied to \u2014");
        System.out.println("proving both directions of the round trip in one connection. A duplicate delivery of");
        System.out.println("the same messageId is deduped (re-acked, not re-persisted/re-replied). Chat history");
        System.out.println("is in " + baseDir.resolve("p2p-chat.sqlite") + ".");
        System.out.println();
        System.out.println("Waiting for a chat message. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    private static void handleChatMessage(ChatMessagePayload incoming, PeerId sender, SignalProtocolAddress remote,
                                           PeerNetworkService network, SecureSessionService sessions,
                                           StorageService storage, HybridLogicalClock clock, String replyText,
                                           boolean markRead) throws Exception {
        // Pre-M6 cleanup pass: reject a message whose HLC timestamp is implausibly far in the
        // future BEFORE this node's own clock is exposed to it at all — checked first, ahead of
        // even the dedup lookup, on the reject-untrusted-input-as-early-as-possible principle.
        // See HybridLogicalClock#checkDrift's own Javadoc for what this does and doesn't cover
        // (only ever the future direction; a stale/slow peer is not rejected here).
        try {
            clock.checkDrift(incoming.hlcTimestamp());
        } catch (HybridLogicalClock.RemoteTimestampRejectedException e) {
            System.out.println("[chat] REJECTED message " + incoming.messageId() + " from " + sender
                    + " \u2014 clock drift too large: " + e.getMessage());
            return; // not persisted, not acked, not replied to, clock not advanced
        }

        // Advances this node's clock so anything sent below is correctly ordered after having
        // observed this event — the return value itself is not what gets persisted below. See
        // this class's own Javadoc for why that distinction matters. Safe to call even for a
        // message that turns out to be a duplicate (below): HybridLogicalClock#update is a pure
        // max()-based advance, calling it twice for the same remote timestamp is harmless.
        clock.update(incoming.hlcTimestamp());

        String ownAddress = DialableAddressResolver.resolve(network.listenAddresses());
        String ownPeerId = extractPeerId(ownAddress);
        String conversationId = deriveDirectConversationId(ownPeerId, sender.value());

        // M5d dedup: spec §15's "duplicate message delivery... → dedup on message_id at the
        // storage layer before it ever reaches the UI." A duplicate is still acknowledged — the
        // sender's own earlier ack may have been lost, and re-acking is idempotent and cheap —
        // but it is NOT re-persisted (would violate the messages primary key anyway) and NOT
        // re-replied-to (re-sending "Hello back" on every retransmit would be actively wrong
        // demo behavior, not a receipt).
        if (storage.hasMessage(incoming.messageId())) {
            System.out.println("[chat] duplicate message " + incoming.messageId() + " ignored (already persisted) \u2014 re-acking anyway");
            byte[] dupAckWire = ChatMessageCodec.encode(new DeliveryReceiptPayload(conversationId, incoming.messageId()));
            EncryptedFrame dupAckFrame = sessions.encrypt(remote, dupAckWire);
            sendAsync(network, incoming.senderAddress(), List.of(dupAckFrame), "duplicate-ack");
            return;
        }

        // Upsert, safe to call on every message — see Conversation/StorageService.saveConversation's
        // own Javadoc. No real contact-naming concept exists yet (M6/M7 territory), so the
        // counterparty's raw peer ID is used as a placeholder display name.
        storage.saveConversation(new Conversation(conversationId, ConversationType.DIRECT, sender.value(), System.currentTimeMillis()));

        storage.saveMessage(new Message(
                incoming.messageId(), conversationId, sender, DeviceId.DEFAULT,
                incoming.hlcTimestamp().toString(), // the ORIGINAL author's timestamp — not clock.update()'s return value
                incoming.contentType(), incoming.content(),
                DeliveryState.DELIVERED, // arrived at this device
                System.currentTimeMillis()));

        System.out.println("[chat] received from " + sender + ": \""
                + new String(incoming.content(), StandardCharsets.UTF_8) + "\" (senderAddress=" + incoming.senderAddress() + ")");
        System.out.println("[chat] persisted to conversation " + conversationId);

        List<EncryptedFrame> outgoing = new ArrayList<>();

        // 1. Delivery receipt — automatic, not a user action, sent for every non-duplicate message.
        byte[] deliveryWire = ChatMessageCodec.encode(new DeliveryReceiptPayload(conversationId, incoming.messageId()));
        outgoing.add(sessions.encrypt(remote, deliveryWire));

        // 2. Read receipt — gated behind markRead, simulating a real "user opened the
        // conversation" action that doesn't exist without a real UI (M7's territory). Updates
        // OUR OWN copy of the just-received message to READ (via updateDeliveryState — this is
        // that method's other real caller, alongside handleDeliveryReceipt below) before telling
        // the sender about it.
        if (markRead) {
            storage.updateDeliveryState(incoming.messageId(), DeliveryState.READ);
            byte[] readWire = ChatMessageCodec.encode(new ReadReceiptPayload(conversationId, incoming.hlcTimestamp()));
            outgoing.add(sessions.encrypt(remote, readWire));
            System.out.println("[chat] marked " + incoming.messageId() + " READ locally; sending a read receipt");
        }

        // 3. The existing M5c chat reply — proves the OTHER direction of the round trip.
        HlcTimestamp replyTimestamp = clock.now();
        String replyMessageId = UUID.randomUUID().toString();
        byte[] replyBytes = replyText.getBytes(StandardCharsets.UTF_8);

        ChatMessagePayload reply = new ChatMessagePayload(
                replyMessageId, ownAddress, replyTimestamp, conversationId, "text/plain", replyBytes, incoming.messageId());

        storage.saveMessage(new Message(
                replyMessageId, conversationId, PeerId.of(ownPeerId), DeviceId.DEFAULT,
                replyTimestamp.toString(), "text/plain", replyBytes,
                DeliveryState.SENT, System.currentTimeMillis()));

        outgoing.add(sessions.encrypt(remote, ChatMessageCodec.encode(reply)));

        // sendEnvelope MUST NOT be called synchronously from here — this method runs on the
        // OnEnvelopeMessage callback's thread, which is jvm-libp2p/Netty's I/O event loop.
        // sendEnvelope blocks internally on the new outbound connection's completion, and that
        // connection's own I/O needs the event loop thread to progress — the same thread that
        // would be blocked waiting for it. Offloading to a separate thread breaks the deadlock.
        // See this class's own Javadoc for the full account of how this was found. M5d sends all
        // three possible outgoing payloads (delivery receipt, optional read receipt, chat reply)
        // from inside ONE async block, not three independent ones, so they can't race each other
        // for the same target connection.
        System.out.println("[chat] dialing sender at: " + incoming.senderAddress() + "...");
        String replyLogText = replyText;
        sendAsync(network, incoming.senderAddress(), outgoing, "delivery-receipt" + (markRead ? "+read-receipt" : "") + "+reply", () -> {
            System.out.println("[chat] replied: \"" + replyLogText + "\" (replyTo=" + incoming.messageId() + ")");
            System.out.println("[chat] Check the sender's console for M5c CONFIRMED.");
        });
    }

    private static void handleDeliveryReceipt(DeliveryReceiptPayload receipt, PeerId sender, StorageService storage) {
        // This acknowledges a message THIS node originally sent — the receipt's own
        // conversationId/messageId are enough to update it directly, no address/session
        // bookkeeping needed since there is nothing further to send in response to a receipt.
        storage.updateDeliveryState(receipt.messageId(), DeliveryState.DELIVERED);
        System.out.println("[chat] delivery receipt from " + sender + " for " + receipt.messageId() + " \u2014 state -> DELIVERED");
    }

    private static void handleReadReceipt(ReadReceiptPayload receipt, PeerId sender, PeerNetworkService network, StorageService storage) {
        String ownPeerId = extractPeerId(DialableAddressResolver.resolve(network.listenAddresses()));
        storage.markMessagesReadUpTo(receipt.conversationId(), PeerId.of(ownPeerId), receipt.readUpToHlcTimestamp().toString());
        System.out.println("[chat] read receipt from " + sender + " \u2014 our messages up to " + receipt.readUpToHlcTimestamp()
                + " in conversation " + receipt.conversationId() + " marked READ");
    }

    /**
     * Encrypts and sends {@code frames} sequentially, one {@code sendEnvelope} call each, from
     * inside a single {@link CompletableFuture#runAsync} block — see the deadlock note on this
     * class's own Javadoc for why this cannot run synchronously from an {@code OnEnvelopeMessage}
     * callback.
     */
    private static void sendAsync(PeerNetworkService network, String targetAddress, List<EncryptedFrame> frames, String label) {
        sendAsync(network, targetAddress, frames, label, () -> { });
    }

    private static void sendAsync(PeerNetworkService network, String targetAddress, List<EncryptedFrame> frames,
                                   String label, Runnable onSuccess) {
        CompletableFuture.runAsync(() -> {
            try {
                for (EncryptedFrame frame : frames) {
                    network.sendEnvelope(targetAddress, EncryptedFrameCodec.encode(frame));
                }
                onSuccess.run();
            } catch (Exception e) {
                // CompletableFuture.runAsync swallows exceptions silently unless caught here —
                // an uncaught failure inside the async task would otherwise vanish with no trace.
                System.out.println("[chat] FAILED to send (" + label + ") to " + targetAddress + ": " + e);
                e.printStackTrace(System.out);
            }
        });
    }

    private static String deriveDirectConversationId(String peerIdA, String peerIdB) {
        // Deterministic and order-independent, so both participants' independent local
        // databases agree on the same conversation_id for the same 1:1 conversation regardless
        // of who sent first — and so that, within ONE peer's own database, its own sent
        // messages and the other side's replies land in the SAME conversation row rather than
        // splitting across two depending on which direction happened first. Unlike
        // FileReceiverMain's "direct-"+senderPeerId placeholder (fine there — file_transfers.
        // conversation_id has no foreign key and is never compared across calls), this value is
        // a real foreign key (M4e) reused across every message in an ongoing conversation, so it
        // has to be internally consistent, not just present.
        return peerIdA.compareTo(peerIdB) <= 0
                ? "direct-" + peerIdA + "-" + peerIdB
                : "direct-" + peerIdB + "-" + peerIdA;
    }

    private static String extractPeerId(String multiaddr) {
        int index = multiaddr.lastIndexOf("/p2p/");
        if (index == -1) {
            throw new IllegalArgumentException("Address does not contain a /p2p/<peer-id> component: " + multiaddr);
        }
        return multiaddr.substring(index + "/p2p/".length());
    }
}
