package com.p2pchat.daemon.session;

import com.p2pchat.filetransfer.FileChunker;
import com.p2pchat.filetransfer.FileKey;
import com.p2pchat.filetransfer.wire.FileChunkPayload;
import com.p2pchat.filetransfer.wire.FileChunkRequestPayload;
import com.p2pchat.filetransfer.wire.FileOfferPayload;
import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.ConnectivityStatus;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.TransferState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link DefaultFileTransferHandler} — two fully independent instances
 * (separate {@link StorageService}, separate temp directories, simulating two separate daemons)
 * wired together by a fake transport in place of real network/crypto. The transport is fake; the
 * chunking, AES-256-GCM encryption/decryption, disk writes, and SHA-256 whole-file hash
 * verification are all completely real — this proves the actual state machine, not a mock of it.
 */
class DefaultFileTransferHandlerTest {

    private SqliteDatabase senderDb;
    private SqliteDatabase receiverDb;

    @AfterEach
    void tearDown() throws SQLException {
        if (senderDb != null) senderDb.close();
        if (receiverDb != null) receiverDb.close();
    }

    @Test
    void happyPathSingleChunkRoundTripsExactBytesAndFiresEvents(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 1024);
        Path sourceFile = h.writeRandomFile("source.bin", 500);
        Path saveFile = h.receiverDir.resolve("received.bin");

        List<Harness.CapturedEvent> events = new CopyOnWriteArrayList<>();
        h.receiverListener = h.captureInto(events);

        String transferId = h.offer(sourceFile);
        assertThat(h.receiverStorage.missingChunks(transferId, 1)).containsExactly(0);
        assertThat(events).anyMatch(e -> e.type().equals("offer"));

        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        assertThat(h.receiverStorage.missingChunks(transferId, 1)).isEmpty();
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile));
        assertThat(events).anyMatch(e -> e.type().equals("progress") && e.state() == TransferState.COMPLETED);
    }

    @Test
    void multiChunkFileWithShortLastChunkReassemblesExactly(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 10); // tiny chunk size forces many chunks, and a short last one
        Path sourceFile = h.writeRandomFile("bigger.bin", 137); // not a multiple of 10
        Path saveFile = h.receiverDir.resolve("received.bin");

        String transferId = h.offer(sourceFile);
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        int totalChunks = FileChunker.chunkCount(137, 10);
        assertThat(totalChunks).isEqualTo(14); // 13 full chunks + 1 short one
        assertThat(h.receiverStorage.missingChunks(transferId, totalChunks)).isEmpty();
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile));
    }

    @Test
    void theAcceptGateGenuinelyBlocksChunkRequestsUntilAccepted(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 1024);
        Path sourceFile = h.writeRandomFile("gated.bin", 100);
        Path saveFile = h.receiverDir.resolve("received.bin");

        AtomicInteger chunkRequestsSeenBySender = new AtomicInteger(0);
        h.onSenderChunkRequestObserved = req -> chunkRequestsSeenBySender.incrementAndGet();

        String transferId = h.offer(sourceFile);
        assertThat(chunkRequestsSeenBySender).hasValue(0);
        assertThat(h.receiverStorage.missingChunks(transferId, 1)).containsExactly(0); // nothing received yet

        h.receiverHandler.acceptFileTransfer(transferId, saveFile);
        assertThat(chunkRequestsSeenBySender).hasValue(1);
    }

    @Test
    void resumeAfterSimulatedRestartRequestsOnlyGenuinelyMissingChunks(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 10);
        Path sourceFile = h.writeRandomFile("resumable.bin", 55); // 6 chunks: indices 0..5
        Path saveFile = h.receiverDir.resolve("received.bin");

        // First attempt: simulate an interruption where only the first 3 chunks (0, 1, 2) arrive before crash
        h.senderChunkFilter = chunk -> chunk.chunkIndex() < 3;
        String transferId = h.offer(sourceFile);
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        // Chunks 0, 1, 2 received; chunks 3, 4, 5 still missing; transfer state is IN_PROGRESS
        assertThat(h.receiverStorage.missingChunks(transferId, 6)).containsExactly(3, 4, 5);
        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.IN_PROGRESS);

        // Simulate a restart: a brand new DefaultFileTransferHandler (fresh in-memory maps), same
        // StorageService (file_chunk_state genuinely persisted)
        AtomicInteger chunksRequestedOnResume = new AtomicInteger(0);
        h.onSenderChunkRequestObserved = req -> chunksRequestedOnResume.addAndGet(req.missingChunkIndices().length);
        h.rebuildReceiverHandlerSimulatingRestart();
        h.senderChunkFilter = chunk -> true; // now allow all chunks through

        // Sender re-offers the in-progress transfer
        h.reOffer(transferId, sourceFile);

        // Receiver accepts re-offer: requests ONLY genuinely missing chunks (3, 4, 5)
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        assertThat(chunksRequestedOnResume).hasValue(3); // only chunks 3, 4, 5 were requested
        assertThat(h.receiverStorage.missingChunks(transferId, 6)).isEmpty();
        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.COMPLETED);
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile));
    }

    @Test
    void aWholeFileHashMismatchIsMarkedFailedNotSilentlyAccepted(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 1024);
        Path sourceFile = h.writeRandomFile("original.bin", 200);
        Path saveFile = h.receiverDir.resolve("received.bin");

        List<Harness.CapturedEvent> events = new CopyOnWriteArrayList<>();
        h.receiverListener = h.captureInto(events);
        h.nextOfferFileHashOverride = "0".repeat(64); // deliberately wrong

        String transferId = h.offer(sourceFile);
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        assertThat(events).anyMatch(e -> e.type().equals("progress") && e.state() == TransferState.FAILED);
    }

    @Test
    void duplicateOfferAfterAcceptDoesNotResetOrCorruptAnAlreadyCompletedTransfer(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 10);
        Path sourceFile = h.writeRandomFile("retry.bin", 55);
        Path saveFile = h.receiverDir.resolve("received.bin");

        String transferId = h.offer(sourceFile);
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile));

        h.reOffer(transferId, sourceFile); // sender retries its own offer send
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile)); // untouched
    }

    @Test
    void outgoingTransfersAreEvictedAfterTtlExpires(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 1024);
        Path sourceFile = h.writeRandomFile("source.bin", 500);

        DefaultFileTransferHandler shortTtlHandler = new DefaultFileTransferHandler(
                h.senderStorage, DaemonEventListener.NONE, Duration.ofMillis(50));

        FileKey key1 = FileKey.generate();
        shortTtlHandler.registerOutgoingTransfer("t1", sourceFile, key1, 1024, h.receiverId, null, null);

        // Wait for TTL to expire
        Thread.sleep(80);

        // Registering a new transfer triggers eviction of t1
        FileKey key2 = FileKey.generate();
        shortTtlHandler.registerOutgoingTransfer("t2", sourceFile, key2, 1024, h.receiverId, null, null);

        // An incoming chunk request for t1 should now be rejected as unknown/expired
        AtomicInteger chunksServed = new AtomicInteger(0);
        shortTtlHandler.attach((target, direct, relay, msg) -> {
            chunksServed.incrementAndGet();
            return CompletableFuture.completedFuture(ConnectivityStatus.DIRECT);
        }, h.senderId, "/ip4/10.0.0.1/tcp/9000/p2p/" + h.senderId.value());

        shortTtlHandler.onFileChunkRequest(h.receiverId, new FileChunkRequestPayload("t1", new int[]{0}));
        assertThat(chunksServed).hasValue(0); // t1 was evicted

        // But t2 is still active
        shortTtlHandler.onFileChunkRequest(h.receiverId, new FileChunkRequestPayload("t2", new int[]{0}));
        assertThat(chunksServed).hasValue(1); // t2 served
    }

    @Test
    void duplicateOfferForAlreadyCompletedTransferIsIgnoredQuietly(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 10);
        Path sourceFile = h.writeRandomFile("completed.bin", 30);
        Path saveFile = h.receiverDir.resolve("received.bin");

        AtomicInteger offerEvents = new AtomicInteger(0);
        h.receiverListener = new DaemonEventListener() {
            @Override
            public void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize) {
                offerEvents.incrementAndGet();
            }
        };

        String transferId = h.offer(sourceFile);
        assertThat(offerEvents).hasValue(1);

        h.receiverHandler.acceptFileTransfer(transferId, saveFile);
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile));
        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.COMPLETED);

        // Receiver daemon restarts (clearing in-memory maps)
        h.rebuildReceiverHandlerSimulatingRestart();

        // Sender re-offers the already-completed transfer
        h.reOffer(transferId, sourceFile);

        // Event listener should NOT be notified again, and transfer state remains COMPLETED
        assertThat(offerEvents).hasValue(1);
        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.COMPLETED);
    }

    @Test
    void failedTransferCanBeRetriedOnReOfferAndSucceeds(@TempDir Path tempDir) throws Exception {
        Harness h = new Harness(tempDir, 10);
        Path sourceFile = h.writeRandomFile("retry-fail.bin", 50);
        Path saveFile = h.receiverDir.resolve("received.bin");

        // First attempt: override expected file hash so whole-file SHA-256 validation fails
        h.nextOfferFileHashOverride = "0000000000000000000000000000000000000000000000000000000000000000";
        String transferId = h.offer(sourceFile);
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.FAILED);
        assertThat(h.receiverStorage.missingChunks(transferId, 5)).isEmpty(); // all chunks were received before hash check

        // Second attempt (retry): sender re-offers with correct hash
        h.reOffer(transferId, sourceFile);

        // State is reset to OFFERED and chunk state is cleared
        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.OFFERED);
        assertThat(h.receiverStorage.missingChunks(transferId, 5)).containsExactly(0, 1, 2, 3, 4);

        // Receiver accepts retry
        h.receiverHandler.acceptFileTransfer(transferId, saveFile);

        // Should complete successfully with matching file content and COMPLETED state
        assertThat(h.receiverStorage.getTransferState(transferId)).isEqualTo(TransferState.COMPLETED);
        assertThat(Files.readAllBytes(saveFile)).isEqualTo(Files.readAllBytes(sourceFile));
    }

    // ---------------------------------------------------------------------------------------

    /** Wires two independent DefaultFileTransferHandler instances together via a fake transport. */
    private class Harness {
        final PeerId senderId = new PeerId("12D3KooWSender1111111111111111111111111111111111");
        final PeerId receiverId = new PeerId("12D3KooWReceiver222222222222222222222222222222222");
        final Path senderDir;
        final Path receiverDir;
        final int chunkSize;
        final StorageService senderStorage;
        final StorageService receiverStorage;
        DefaultFileTransferHandler senderHandler;
        DefaultFileTransferHandler receiverHandler;
        DaemonEventListener receiverListener = DaemonEventListener.NONE;
        Consumer<FileChunkRequestPayload> onSenderChunkRequestObserved = req -> {
        };
        java.util.function.Predicate<FileChunkPayload> senderChunkFilter = chunk -> true;
        String nextOfferFileHashOverride = null;

        private final DaemonEventListener receiverListenerDelegate = new DaemonEventListener() {
            @Override
            public void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize) {
                receiverListener.onFileOfferReceived(transferId, sender, fileName, fileSize);
            }

            @Override
            public void onFileTransferProgress(String transferId, int chunksReceived, int totalChunks, TransferState state) {
                receiverListener.onFileTransferProgress(transferId, chunksReceived, totalChunks, state);
            }
        };

        Harness(Path tempDir, int chunkSize) throws Exception {
            this.chunkSize = chunkSize;
            senderDir = Files.createDirectory(tempDir.resolve("sender-files"));
            receiverDir = Files.createDirectory(tempDir.resolve("receiver-files"));
            senderDb = SqliteDatabase.openOrCreate(Files.createDirectory(tempDir.resolve("sender-db")));
            receiverDb = SqliteDatabase.openOrCreate(Files.createDirectory(tempDir.resolve("receiver-db")));
            this.senderStorage = new SqliteStorageService(senderDb);
            this.receiverStorage = new SqliteStorageService(receiverDb);
            rebuildBoth();
        }

        void rebuildBoth() {
            senderHandler = new DefaultFileTransferHandler(senderStorage, DaemonEventListener.NONE);
            receiverHandler = new DefaultFileTransferHandler(receiverStorage, receiverListenerDelegate);
            senderHandler.attach(this::routeFromSender, senderId, "/ip4/10.0.0.1/tcp/9000/p2p/" + senderId.value());
            receiverHandler.attach(this::routeFromReceiver, receiverId, "/ip4/10.0.0.2/tcp/9000/p2p/" + receiverId.value());
        }

        void rebuildReceiverHandlerSimulatingRestart() {
            receiverHandler = new DefaultFileTransferHandler(receiverStorage, receiverListenerDelegate);
            receiverHandler.attach(this::routeFromReceiver, receiverId, "/ip4/10.0.0.2/tcp/9000/p2p/" + receiverId.value());
        }

        Path writeRandomFile(String name, int size) throws Exception {
            Path file = senderDir.resolve(name);
            byte[] data = new byte[size];
            new Random(42).nextBytes(data);
            Files.write(file, data);
            return file;
        }

        String offer(Path sourceFile) throws Exception {
            long fileSize = Files.size(sourceFile);
            int totalChunks = FileChunker.chunkCount(fileSize, chunkSize);
            String fileHash = nextOfferFileHashOverride != null ? nextOfferFileHashOverride : FileChunker.sha256HexOfFile(sourceFile);
            nextOfferFileHashOverride = null;
            FileKey fileKey = FileKey.generate();
            String transferId = UUID.randomUUID().toString();

            senderHandler.registerOutgoingTransfer(transferId, sourceFile, fileKey, chunkSize,
                    receiverId, "/ip4/10.0.0.2/tcp/9000/p2p/" + receiverId.value(), null);

            FileOfferPayload offer = new FileOfferPayload(transferId, "/ip4/10.0.0.1/tcp/9000/p2p/" + senderId.value(),
                    sourceFile.getFileName().toString(), fileSize, fileHash, chunkSize, totalChunks, fileKey.bytes());
            receiverHandler.onFileOffer(senderId, offer);
            return transferId;
        }

        void reOffer(String transferId, Path sourceFile) throws Exception {
            long fileSize = Files.size(sourceFile);
            int totalChunks = FileChunker.chunkCount(fileSize, chunkSize);
            String fileHash = FileChunker.sha256HexOfFile(sourceFile);
            FileKey freshKey = FileKey.generate(); // a real re-offer reuses the same key; a fresh one proves the same thing here
            senderHandler.registerOutgoingTransfer(transferId, sourceFile, freshKey, chunkSize,
                    receiverId, "/ip4/10.0.0.2/tcp/9000/p2p/" + receiverId.value(), null);
            FileOfferPayload offer = new FileOfferPayload(transferId, "/ip4/10.0.0.1/tcp/9000/p2p/" + senderId.value(),
                    sourceFile.getFileName().toString(), fileSize, fileHash, chunkSize, totalChunks, freshKey.bytes());
            receiverHandler.onFileOffer(senderId, offer);
        }

        CompletableFuture<ConnectivityStatus> routeFromSender(PeerId target, String direct, String relay, FileTransferMessage message) {
            if (message instanceof FileChunkPayload chunk) {
                if (senderChunkFilter.test(chunk)) {
                    receiverHandler.onFileChunk(senderId, chunk);
                }
            }
            return CompletableFuture.completedFuture(ConnectivityStatus.DIRECT);
        }

        CompletableFuture<ConnectivityStatus> routeFromReceiver(PeerId target, String direct, String relay, FileTransferMessage message) {
            if (message instanceof FileChunkRequestPayload req) {
                onSenderChunkRequestObserved.accept(req);
                senderHandler.onFileChunkRequest(receiverId, req);
            }
            return CompletableFuture.completedFuture(ConnectivityStatus.DIRECT);
        }

        record CapturedEvent(String type, TransferState state) {
        }

        DaemonEventListener captureInto(List<CapturedEvent> sink) {
            return new DaemonEventListener() {
                @Override
                public void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize) {
                    sink.add(new CapturedEvent("offer", null));
                }

                @Override
                public void onFileTransferProgress(String transferId, int chunksReceived, int totalChunks, TransferState state) {
                    sink.add(new CapturedEvent("progress", state));
                }
            };
        }
    }
}
