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
 * on the first real test run — {@link #firstDialableAddress} is the identical fix, needed here
 * too since this process also self-reports its address as a {@link ChatMessagePayload#senderAddress}.
 * (The Netty-event-loop deadlock M5c also found doesn't apply here: this process's own
 * {@code sendEnvelope} call happens in {@code main}'s synchronous flow, not inside the
 * {@code OnEnvelopeMessage} callback.)
 */
public class ChatSenderMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runChatSender \\");
            System.out.println("           -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" \\");
            System.out.println("           -Pbundlefile=\"<path to the listener's published-bundle.b64>\" \\");
            System.out.println("           -Pmessage=\"your message here\" \\");
            System.out.println("           -Pport=9201 (optional; this node's own listening port, needed to receive the reply back)");
            return;
        }

        String listenerAddress = args[0];
        String bundleFilePath = args[1];
        String messageText = args[2];
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 9201;

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
                    clock.update(reply.hlcTimestamp()); // advances local clock; NOT what gets persisted below — see ChatListenerMain's Javadoc

                    String ownPeerId = extractPeerId(network.listenAddresses()[0]);
                    String conversationId = deriveDirectConversationId(ownPeerId, sender.value());

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
                } else {
                    System.out.println("[chat] unexpected message type from " + sender + " (M5d territory): " + message);
                }
            } catch (Exception e) {
                System.out.println("[chat] FAILED to process message from " + sender + ": " + e);
            }
        });

        String bundleBase64 = Files.readString(Path.of(bundleFilePath)).trim();
        PreKeyBundle remoteBundle = PreKeyBundleCodec.decode(Base64.getDecoder().decode(bundleBase64));
        sessions.establishSession(remoteSignalAddress, remoteBundle);
        System.out.println("PQXDH session established with " + remotePeerId);

        String ownAddress = firstDialableAddress(network.listenAddresses());
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

        System.out.println("Sent: \"" + messageText + "\" (messageId=" + messageId + ")");
        System.out.println("Persisted to conversation " + conversationId + " in " + baseDir.resolve("p2p-chat.sqlite"));
        System.out.println("Waiting for the listener's reply. Press Ctrl+C to stop once M5c CONFIRMED prints.");

        Thread.currentThread().join();
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

    /**
     * Resolves a wildcard bind address to a concrete, dialable one. Identical fix and identical
     * reasoning to {@code ChatListenerMain}'s copy of this method — see its Javadoc for the full
     * account, including why this is a same-machine-testing fix, not a general one.
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
