package com.p2pchat.daemon;

import com.p2pchat.crypto.EncryptedFrame;
import com.p2pchat.crypto.EncryptedFrameCodec;
import com.p2pchat.crypto.LibsignalSecureSessionService;
import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.SecureSessionService;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.filetransfer.ChunkCipher;
import com.p2pchat.filetransfer.EncryptedChunk;
import com.p2pchat.filetransfer.FileChunker;
import com.p2pchat.filetransfer.FileKey;
import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.filetransfer.wire.FileTransferMessageCodec;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M4c: the sending side. Establishes a PQXDH session with the receiver (the same role
 * SecureSenderMain played in M2c — needs the OTHER side's published bundle) and sends a
 * FileOfferPayload. Unlike SecureSenderMain, which sent one message and exited after a short
 * pause, this stays running afterward: it's the first milestone where the "sender" also has to
 * act as a listener, to receive and answer the FileChunkRequestPayload that comes back.
 *
 * No resume in M4c — see FileReceiverMain's Javadoc.
 */
public class FileSenderMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ./gradlew :node-daemon:runFileSender \\");
            System.out.println("           -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" \\");
            System.out.println("           -Pbundlefile=\"<path to the receiver's published-bundle.b64>\" \\");
            System.out.println("           -Pfile=\"<path to a local file to send>\" \\");
            System.out.println("           -Pport=9000 (optional; this node's own listening port, needed to receive the chunk request back)");
            System.out.println("           -Pchunksize=16 (optional; forces small chunks so a small test file has multiple chunks, for testing M4d resume)");
            return;
        }

        String receiverAddress = args[0];
        String bundleFilePath = args[1];
        Path fileToSend = Path.of(args[2]);
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 9000;
        // Optional 5th arg, mainly for testing M4d's resume behavior with a small file: the
        // default 256 KB means a small test file is always exactly 1 chunk, which can't
        // exercise partial-transfer resume. A tiny override (e.g. 16) forces several chunks.
        int chunkSizeOverride = args.length > 4 ? Integer.parseInt(args[4]) : -1;

        if (!Files.isRegularFile(fileToSend)) {
            System.out.println("Not a file: " + fileToSend);
            return;
        }

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

        // Needed up front for establishSession() (which happens before any inbound connection
        // exists, so there's no "actual sender" callback value available yet) — matches
        // SecureSenderMain exactly. Once inside the callback below, the fresh `sender` param is
        // used instead, same reasoning as FileReceiverMain.
        String remotePeerId = extractPeerId(receiverAddress);
        SignalProtocolAddress remoteSignalAddress = new SignalProtocolAddress(remotePeerId, 1);

        // Chunking + key generation — same primitives M4a already proved in isolation.
        int chunkSize = chunkSizeOverride > 0 ? chunkSizeOverride : FileChunker.DEFAULT_CHUNK_SIZE_BYTES;
        long fileSize = Files.size(fileToSend);
        int totalChunks = FileChunker.chunkCount(fileSize, chunkSize);
        String fileHash = FileChunker.sha256HexOfFile(fileToSend);
        FileKey fileKey = FileKey.generate();
        String transferId = "t_" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Path> outgoingTransfers = new ConcurrentHashMap<>();
        outgoingTransfers.put(transferId, fileToSend);

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, identityService.rawPrivateKeySeed(), (sender, data) -> {
            try {
                SignalProtocolAddress remote = new SignalProtocolAddress(sender.value(), 1);

                EncryptedFrame frame = EncryptedFrameCodec.decode(data);
                byte[] plaintext = sessions.decrypt(remote, frame);
                FileTransferMessage message = FileTransferMessageCodec.decode(plaintext);

                if (message instanceof FileChunkRequestPayload request) {
                    Path source = outgoingTransfers.get(request.transferId());
                    if (source == null) {
                        System.out.println("[file] chunk request for unknown transfer " + request.transferId() + " - ignoring");
                        return;
                    }
                    System.out.println("[file] chunk request received: " + request.missingChunkIndices().length + " chunk(s) requested");

                    for (int chunkIndex : request.missingChunkIndices()) {
                        byte[] plaintextChunk = FileChunker.readChunk(source, chunkIndex, chunkSize);
                        EncryptedChunk encrypted = ChunkCipher.encrypt(fileKey, chunkIndex, plaintextChunk);
                        FileChunkPayload chunkPayload = new FileChunkPayload(
                                request.transferId(), chunkIndex, encrypted.nonce(), encrypted.ciphertext());
                        sendMessage(network, sessions, receiverAddress, remote, chunkPayload);
                        System.out.println("[file] sent chunk " + chunkIndex);
                    }
                    System.out.println();
                    System.out.println("All requested chunks sent. Check the receiver's console for M4c CONFIRMED.");

                } else {
                    System.out.println("[file] unexpected message type from " + sender + ": " + message);
                }
            } catch (Exception e) {
                System.out.println("[file] FAILED to process message from " + sender + ": " + e);
            }
        });

        String bundleBase64 = Files.readString(Path.of(bundleFilePath)).trim();
        PreKeyBundle remoteBundle = PreKeyBundleCodec.decode(Base64.getDecoder().decode(bundleBase64));
        sessions.establishSession(remoteSignalAddress, remoteBundle);
        System.out.println("PQXDH session established with " + remotePeerId);

        // network.listenAddresses() is already populated by this point — network.start() ran
        // above. Reporting our own address here is what lets the receiver reply without ever
        // being told our address in advance; see FileOfferPayload's Javadoc for why.
        String ownAddress = network.listenAddresses()[0];
        FileOfferPayload offer = new FileOfferPayload(
                transferId, ownAddress, fileToSend.getFileName().toString(), fileSize, fileHash, chunkSize, totalChunks, fileKey.bytes());
        sendMessage(network, sessions, receiverAddress, remoteSignalAddress, offer);

        System.out.println("Offer sent: \"" + offer.fileName() + "\" (" + fileSize + " bytes, " + totalChunks + " chunks)");
        System.out.println("Waiting for the chunk request. Press Ctrl+C to stop once the transfer completes.");

        Thread.currentThread().join();
    }

    private static void sendMessage(PeerNetworkService network, SecureSessionService sessions,
                                     String targetAddress, SignalProtocolAddress remote,
                                     FileTransferMessage message) throws Exception {
        byte[] plaintext = FileTransferMessageCodec.encode(message);
        EncryptedFrame frame = sessions.encrypt(remote, plaintext);
        network.sendEnvelope(targetAddress, EncryptedFrameCodec.encode(frame));
    }

    private static String extractPeerId(String multiaddr) {
        int index = multiaddr.lastIndexOf("/p2p/");
        if (index == -1) {
            throw new IllegalArgumentException("Address does not contain a /p2p/<peer-id> component: " + multiaddr);
        }
        return multiaddr.substring(index + "/p2p/".length());
    }
}
