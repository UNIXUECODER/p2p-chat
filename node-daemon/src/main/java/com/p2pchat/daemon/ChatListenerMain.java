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
import com.p2pchat.model.DeviceId;
import com.p2pchat.model.PeerId;
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
import java.util.Base64;
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
 *   all interfaces" address itself, which nothing can actually connect <i>to</i>. {@link
 *   #firstDialableAddress} resolves this to loopback for same-machine testing — see its own
 *   Javadoc for why that's a demo-scoped fix, not a general one.</li>
 *   <li>{@code network.sendEnvelope(...)} was being called synchronously from inside this
 *   {@code OnEnvelopeMessage} callback — which runs on jvm-libp2p/Netty's I/O event loop thread.
 *   {@code sendEnvelope} blocks internally waiting on the new outbound connection's completion,
 *   but that connection's own I/O also needs the event loop thread to make progress — the same
 *   thread the blocking wait is stuck on. Deadlock: the reply dial can never complete because
 *   the thread that would drive it to completion is the one blocked waiting for it. Fixed by
 *   moving the {@code sendEnvelope} call onto a separate thread via {@link CompletableFuture#runAsync}.</li>
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
 */
public class ChatListenerMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9200;
        String replyText = args.length > 1 ? args[1] : "Hello back \u2014 this is an M5c automatic reply.";

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
                    handleChatMessage(incoming, sender, remote, network, sessions, storage, clock, replyText);
                } else {
                    // DeliveryReceiptPayload/ReadReceiptPayload aren't sent by anything in M5c —
                    // that's M5d's job. Handled defensively rather than silently, same pattern
                    // FileReceiverMain's "unexpected message type" branch already established.
                    System.out.println("[chat] unexpected message type from " + sender + " (M5d territory): " + message);
                }
            } catch (Exception e) {
                System.out.println("[chat] FAILED to process message from " + sender + ": " + e);
            }
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Data dir     : " + baseDir);
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
        System.out.println("core-storage) and automatically replied to, proving both directions of the");
        System.out.println("round trip in one connection. Chat history is in " + baseDir.resolve("p2p-chat.sqlite") + ".");
        System.out.println();
        System.out.println("Waiting for a chat message. Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    private static void handleChatMessage(ChatMessagePayload incoming, PeerId sender, SignalProtocolAddress remote,
                                           PeerNetworkService network, SecureSessionService sessions,
                                           StorageService storage, HybridLogicalClock clock, String replyText) throws Exception {
        // Advances this node's clock so the reply below is correctly ordered after having
        // observed this event — the return value itself is not what gets persisted below. See
        // this class's own Javadoc for why that distinction matters.
        clock.update(incoming.hlcTimestamp());

        String ownAddress = firstDialableAddress(network.listenAddresses());
        String ownPeerId = extractPeerId(ownAddress);
        String conversationId = deriveDirectConversationId(ownPeerId, sender.value());

        // Upsert, safe to call on every message — see Conversation/StorageService.saveConversation's
        // own Javadoc. No real contact-naming concept exists yet (M6/M7 territory), so the
        // counterparty's raw peer ID is used as a placeholder display name.
        storage.saveConversation(new Conversation(conversationId, ConversationType.DIRECT, sender.value(), System.currentTimeMillis()));

        storage.saveMessage(new Message(
                incoming.messageId(), conversationId, sender, DeviceId.DEFAULT,
                incoming.hlcTimestamp().toString(), // the ORIGINAL author's timestamp — not clock.update()'s return value
                incoming.contentType(), incoming.content(),
                DeliveryState.DELIVERED, // arrived at this device; "READ" is a real user action, M5d's territory
                System.currentTimeMillis()));

        System.out.println("[chat] received from " + sender + ": \""
                + new String(incoming.content(), StandardCharsets.UTF_8) + "\" (senderAddress=" + incoming.senderAddress() + ")");
        System.out.println("[chat] persisted to conversation " + conversationId);

        // Compose and send the reply — proves the OTHER direction of the round trip.
        HlcTimestamp replyTimestamp = clock.now();
        String replyMessageId = UUID.randomUUID().toString();
        byte[] replyBytes = replyText.getBytes(StandardCharsets.UTF_8);

        ChatMessagePayload reply = new ChatMessagePayload(
                replyMessageId, ownAddress, replyTimestamp, conversationId, "text/plain", replyBytes, incoming.messageId());

        storage.saveMessage(new Message(
                replyMessageId, conversationId, PeerId.of(ownPeerId), DeviceId.DEFAULT,
                replyTimestamp.toString(), "text/plain", replyBytes,
                DeliveryState.SENT, System.currentTimeMillis()));

        byte[] wire = ChatMessageCodec.encode(reply);
        EncryptedFrame frame = sessions.encrypt(remote, wire);

        // sendEnvelope MUST NOT be called synchronously from here — this method runs on the
        // OnEnvelopeMessage callback's thread, which is jvm-libp2p/Netty's I/O event loop.
        // sendEnvelope blocks internally on the new outbound connection's completion, and that
        // connection's own I/O needs the event loop thread to progress — the same thread that
        // would be blocked waiting for it. Offloading to a separate thread breaks the deadlock.
        // See this class's own Javadoc for the full account of how this was found.
        System.out.println("[chat] dialing sender at: " + incoming.senderAddress() + "...");
        CompletableFuture.runAsync(() -> {
            try {
                network.sendEnvelope(incoming.senderAddress(), EncryptedFrameCodec.encode(frame));
                System.out.println("[chat] replied: \"" + replyText + "\" (replyTo=" + incoming.messageId() + ")");
                System.out.println("[chat] Check the sender's console for M5c CONFIRMED.");
            } catch (Exception e) {
                // CompletableFuture.runAsync swallows exceptions silently unless caught here —
                // an uncaught failure inside the async task would otherwise vanish with no trace.
                System.out.println("[chat] FAILED to send reply to " + incoming.senderAddress() + ": " + e);
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

    /**
     * Resolves a wildcard bind address ({@code 0.0.0.0} / {@code ::}, meaning "listening on
     * every interface") to a concrete address something can actually dial. Found necessary on
     * the first real test run: {@code network.listenAddresses()} returned
     * {@code /ip6/::/tcp/<port>/...} on Windows — the wildcard itself, not a usable address — so
     * a self-reported {@link ChatMessagePayload#senderAddress} built directly from it was
     * undialable.
     *
     * <p><b>This resolves to loopback ({@code 127.0.0.1}), which is a same-machine-testing
     * fix, not a general one.</b> M5c's demo runs both processes on one machine, so loopback is
     * always correct here. It would <i>not</i> be correct for two genuinely different physical
     * machines — that needs real address discovery (a real LAN IP, or STUN-style external
     * address discovery), neither of which exists anywhere in this project yet. Worth flagging
     * for M6/M7: this exact problem — "what address should I tell a peer to reach me at" — will
     * need a real answer once this isn't just a same-machine demo.
     */
    private static String firstDialableAddress(String[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        for (String addr : addresses) {
            if (addr.startsWith("/ip4/")) {
                return addr.replace("/ip4/0.0.0.0/", "/ip4/127.0.0.1/");
            }
        }
        String first = addresses[0];
        if (first.startsWith("/ip6/::/")) {
            return first.replace("/ip6/::/", "/ip4/127.0.0.1/");
        }
        return first;
    }
}
