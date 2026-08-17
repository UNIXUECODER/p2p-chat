package com.p2pchat.daemon;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleCodec;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M5c: the sending side. Establishes a PQXDH session with the listener (the same role
 * {@code SecureSenderMain}/{@code FileSenderMain} played in M2c/M4c) and sends a
 * {@code ChatMessagePayload}. Unlike {@code SecureSenderMain}, which sent one message and
 * exited after a short pause, this stays running afterward — it needs to receive the listener's
 * reply to prove the round trip actually works in both directions, not just one.
 *
 * <p>See {@code ChatListenerMain}'s Javadoc for the correctness note on
 * {@link HybridLogicalClock#update}'s return value never being what gets persisted as a
 * received message's own {@code hlc_timestamp} — applies identically here for the reply this
 * process receives back. Also see that class's Javadoc for the wildcard-bind-address bug found
 * on the first real test run, and its M5d update — {@link DialableAddressResolver} (promoted out
 * of both classes' private, duplicated {@code firstDialableAddress} methods this milestone) is
 * used here too, since this process also self-reports its address as a
 * {@link ChatMessagePayload#senderAddress}. (The Netty-event-loop deadlock M5c found in
 * {@code ChatListenerMain} doesn't apply here for the ORIGINAL outgoing message — that
 * {@code sendEnvelope} call happens in {@code main}'s synchronous flow, not inside the
 * {@code OnEnvelopeMessage} callback. It DOES apply to M5d's new sends from inside the callback
 * below — DeliveryReceiptPayload/ReadReceiptPayload acknowledging the listener's reply — so those
 * reuse {@code ChatListenerMain}'s async-send pattern.)
 *
 * <p><b>M5d update — this side now also dedups, auto-acks, and can request/produce read
 * receipts:</b> the inbound reply is checked against {@link StorageService#hasMessage} before
 * being persisted (same reasoning as {@code ChatListenerMain}); a non-duplicate reply gets a
 * {@link DeliveryReceiptPayload} sent back automatically, plus a {@link ReadReceiptPayload} if
 * {@code markRead} is set; and an inbound {@link DeliveryReceiptPayload}/{@link ReadReceiptPayload}
 * (acknowledging the ORIGINAL message this process sent at startup) updates that message's own
 * stored state.
 *
 * <p><b>Pre-M6 cleanup pass — remote clock-drift guard.</b> Same addition, same reasoning, as
 * {@code ChatListenerMain}'s own Javadoc describes — see that class for the full account.
 *
 * <p><b>Pre-M6 cleanup pass — {@code duplicatesend}, a permanent test hook for M5d's dedup
 * path.</b> M5d's own storage-layer JUnit tests cover {@code StorageService#hasMessage} in
 * isolation, but nothing had exercised the real, live, wire-level path — a message with a
 * {@code messageId} the listener has already seen actually arriving over the network a second
 * time. {@code -Pduplicatesend=true} makes this process send its original message twice (fresh
 * Signal encryption each time — see the flag's own inline comment for why that's the realistic
 * simulation, not a shortcut), so a real two-terminal run can confirm the listener's console
 * shows exactly one "duplicate message ignored" line and the resulting SQLite database has
 * exactly one row for that {@code messageId}, not two.
 */
public class ChatSenderMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runChatSender \\");
            System.out.println("           -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" \\");
            System.out.println("           -Pbundlefile=\"<path to the listener's published-bundle.b64>\" \\");
            System.out.println("           -Pmessage=\"your message here\" \\");
            System.out.println("           -Pport=9201 (optional; this node's own listening port, needed to receive the reply back) \\");
            System.out.println("           -Pmarkread=true (optional; sends a read receipt for the listener's reply once received) \\");
            System.out.println("           -Pduplicatesend=true (optional; sends the same messageId a second time, to exercise the listener's dedup path)");
            return;
        }

        String listenerAddress = args[0];
        String bundleFilePath = args[1];
        String messageText = args[2];
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 9201;
        boolean markRead = args.length > 4 && Boolean.parseBoolean(args[4]);
        boolean duplicateSend = args.length > 5 && Boolean.parseBoolean(args[5]);

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

        SqliteDatabase database = SqliteDatabase.openOrCreate(baseDir);
        StorageService storage = new SqliteStorageService(database);

        HybridLogicalClock clock = new HybridLogicalClock(identity.peerId());

        // Needed up front for establishSession() (no inbound connection exists yet to read a
        // "sender" value from) — matches SecureSenderMain/FileSenderMain exactly. Once inside
        // the callback below, the fresh `sender` param is used instead.
        String remotePeerId = extractPeerId(listenerAddress);
        SignalProtocolAddress remoteSignalAddress = new SignalProtocolAddress(remotePeerId, 1);

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            try {
                SignalProtocolAddress remote = new SignalProtocolAddress(sender.value(), 1);

                EncryptedFrame frame = EncryptedFrameCodec.decode(data);
                byte[] plaintext = sessions.decrypt(remote, frame);
                ChatWireMessage message = ChatMessageCodec.decode(plaintext);

                if (message instanceof ChatMessagePayload reply) {
                    handleReply(reply, sender, remote, network, sessions, storage, clock, markRead);
                } else if (message instanceof DeliveryReceiptPayload receipt) {
                    storage.updateDeliveryState(receipt.messageId(), DeliveryState.DELIVERED);
                    System.out.println("[chat] delivery receipt from " + sender + " for " + receipt.messageId() + " \u2014 state -> DELIVERED");
                } else if (message instanceof ReadReceiptPayload receipt) {
                    String ownPeerId = extractPeerId(DialableAddressResolver.resolve(network.listenAddresses()));
                    storage.markMessagesReadUpTo(receipt.conversationId(), PeerId.of(ownPeerId), receipt.readUpToHlcTimestamp().toString());
                    System.out.println("[chat] read receipt from " + sender + " \u2014 our messages up to " + receipt.readUpToHlcTimestamp()
                            + " in conversation " + receipt.conversationId() + " marked READ");
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

        String bundleBase64 = Files.readString(Path.of(bundleFilePath)).trim();
        PreKeyBundle remoteBundle = PreKeyBundleCodec.decode(Base64.getDecoder().decode(bundleBase64));
        sessions.establishSession(remoteSignalAddress, remoteBundle);
        System.out.println("PQXDH session established with " + remotePeerId);

        String ownAddress = DialableAddressResolver.resolve(network.listenAddresses());
        String ownPeerId = extractPeerId(ownAddress);
        String conversationId = deriveDirectConversationId(ownPeerId, remotePeerId);

        HlcTimestamp timestamp = clock.now();
        String messageId = UUID.randomUUID().toString();
        byte[] messageBytes = messageText.getBytes(StandardCharsets.UTF_8);

        ChatMessagePayload outgoing = new ChatMessagePayload(
                messageId, ownAddress, timestamp, conversationId, "text/plain", messageBytes, null);

        storage.saveConversation(new Conversation(conversationId, ConversationType.DIRECT, remotePeerId, System.currentTimeMillis()));
        storage.saveMessage(new Message(
                messageId, conversationId, PeerId.of(ownPeerId), DeviceId.DEFAULT,
                timestamp.toString(), "text/plain", messageBytes,
                DeliveryState.SENT, System.currentTimeMillis()));

        byte[] wire = ChatMessageCodec.encode(outgoing);
        EncryptedFrame frame = sessions.encrypt(remoteSignalAddress, wire);
        network.sendEnvelope(listenerAddress, EncryptedFrameCodec.encode(frame));

        if (duplicateSend) {
            // Pre-M6 cleanup pass — exercises the real, live wire-level dedup path end-to-end
            // (the checklist item behind this: send the same ChatMessagePayload.messageId
            // twice; confirm it is re-acked; confirm it is not persisted twice; confirm the
            // listener does not auto-reply twice). Re-encrypting the SAME plaintext (`wire`,
            // unchanged — same messageId, same content) rather than trying to resend identical
            // bytes: the Signal Double Ratchet advances on every encrypt() call by design, so a
            // byte-identical resend isn't how a real retransmission would look anyway — a fresh
            // ratchet message carrying the same messageId is the realistic simulation, and it's
            // exactly what the listener's dedup check (keyed on messageId, not on wire bytes) is
            // meant to catch.
            EncryptedFrame duplicateFrame = sessions.encrypt(remoteSignalAddress, wire);
            network.sendEnvelope(listenerAddress, EncryptedFrameCodec.encode(duplicateFrame));
            System.out.println("[chat] duplicatesend: sent messageId " + messageId
                    + " a second time \u2014 check the listener's console for the dedup log line");
        }

        System.out.println("Sent: \"" + messageText + "\" (messageId=" + messageId + ")");
        System.out.println("Persisted to conversation " + conversationId + " in " + baseDir.resolve("p2p-chat.sqlite"));
        System.out.println("Mark-read: " + (markRead ? "ON" : "OFF") + ", duplicate-send: " + (duplicateSend ? "ON" : "OFF")
                + " \u2014 waiting for the listener's reply. Press Ctrl+C to stop once M5c CONFIRMED prints.");

        Thread.currentThread().join();
    }

    private static void handleReply(ChatMessagePayload reply, PeerId sender, SignalProtocolAddress remote,
                                     PeerNetworkService network, SecureSessionService sessions, StorageService storage,
                                     HybridLogicalClock clock, boolean markRead) throws Exception {
        // Pre-M6 cleanup pass — identical guard and identical reasoning to ChatListenerMain's own
        // handleChatMessage: reject before this node's own clock is exposed to the value at all.
        try {
            clock.checkDrift(reply.hlcTimestamp());
        } catch (HybridLogicalClock.RemoteTimestampRejectedException e) {
            System.out.println("[chat] REJECTED reply " + reply.messageId() + " from " + sender
                    + " \u2014 clock drift too large: " + e.getMessage());
            return; // not persisted, not acked, clock not advanced
        }

        clock.update(reply.hlcTimestamp()); // advances local clock; NOT what gets persisted below — see ChatListenerMain's Javadoc

        String ownPeerId = extractPeerId(DialableAddressResolver.resolve(network.listenAddresses()));
        String conversationId = deriveDirectConversationId(ownPeerId, sender.value());

        // M5d dedup — identical reasoning to ChatListenerMain's own handleChatMessage.
        if (storage.hasMessage(reply.messageId())) {
            System.out.println("[chat] duplicate reply " + reply.messageId() + " ignored (already persisted) \u2014 re-acking anyway");
            byte[] dupAckWire = ChatMessageCodec.encode(new DeliveryReceiptPayload(conversationId, reply.messageId()));
            EncryptedFrame dupAckFrame = sessions.encrypt(remote, dupAckWire);
            sendAsync(network, reply.senderAddress(), dupAckFrame, "duplicate-ack");
            return;
        }

        storage.saveConversation(new Conversation(conversationId, ConversationType.DIRECT, sender.value(), System.currentTimeMillis()));
        storage.saveMessage(new Message(
                reply.messageId(), conversationId, sender, DeviceId.DEFAULT,
                reply.hlcTimestamp().toString(), reply.contentType(), reply.content(),
                DeliveryState.DELIVERED, System.currentTimeMillis()));

        System.out.println("[chat] reply received from " + sender + ": \""
                + new String(reply.content(), StandardCharsets.UTF_8) + "\"");
        System.out.println("[chat] persisted to conversation " + conversationId);
        System.out.println();
        System.out.println("M5c CONFIRMED: a chat message was sent, received, persisted, and replied to \u2014");
        System.out.println("and the reply itself was received, persisted, and correctly matched back to this");
        System.out.println("conversation \u2014 real bidirectional 1:1 messaging, over a real encrypted connection.");

        // M5d: auto-ack the reply with a delivery receipt, and optionally a read receipt — same
        // "compose everything, send everything from one async block" reasoning as
        // ChatListenerMain's own handleChatMessage, and for the same underlying reason: this
        // runs on the OnEnvelopeMessage/Netty event-loop thread, so sendEnvelope cannot be called
        // synchronously here.
        byte[] deliveryWire = ChatMessageCodec.encode(new DeliveryReceiptPayload(conversationId, reply.messageId()));
        EncryptedFrame deliveryFrame = sessions.encrypt(remote, deliveryWire);

        if (markRead) {
            storage.updateDeliveryState(reply.messageId(), DeliveryState.READ);
            byte[] readWire = ChatMessageCodec.encode(new ReadReceiptPayload(conversationId, reply.hlcTimestamp()));
            EncryptedFrame readFrame = sessions.encrypt(remote, readWire);
            System.out.println("[chat] marked " + reply.messageId() + " READ locally; sending a read receipt");
            sendAsync(network, reply.senderAddress(), List.of(deliveryFrame, readFrame), "delivery-receipt+read-receipt");
        } else {
            sendAsync(network, reply.senderAddress(), deliveryFrame, "delivery-receipt");
        }
    }

    /** Single-frame convenience overload — see the {@code List}-taking overload for the real logic. */
    private static void sendAsync(PeerNetworkService network, String targetAddress, EncryptedFrame frame, String label) {
        sendAsync(network, targetAddress, List.of(frame), label);
    }

    /**
     * Encrypts and sends {@code frames} sequentially, one {@code sendEnvelope} call each, from
     * inside a single {@link CompletableFuture#runAsync} block — identical pattern and identical
     * reasoning to {@code ChatListenerMain}'s own {@code sendAsync}.
     */
    private static void sendAsync(PeerNetworkService network, String targetAddress, List<EncryptedFrame> frames, String label) {
        CompletableFuture.runAsync(() -> {
            try {
                for (EncryptedFrame frame : frames) {
                    network.sendEnvelope(targetAddress, EncryptedFrameCodec.encode(frame));
                }
                System.out.println("[chat] sent (" + label + ") to " + targetAddress);
            } catch (Exception e) {
                System.out.println("[chat] FAILED to send (" + label + ") to " + targetAddress + ": " + e);
                e.printStackTrace(System.out);
            }
        });
    }

    private static String deriveDirectConversationId(String peerIdA, String peerIdB) {
        // Must match ChatListenerMain's derivation exactly — see that class's Javadoc for why
        // this needs to be deterministic and order-independent, not just present.
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
