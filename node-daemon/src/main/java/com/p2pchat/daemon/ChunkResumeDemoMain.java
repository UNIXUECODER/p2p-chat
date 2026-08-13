package com.p2pchat.daemon;

import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.TransferState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M4d: proves chunk-level resume works against a real SQLite database, with a genuinely
 * simulated process restart — not just an assumption that it would. No networking, no crypto,
 * same "prove the new mechanism in isolation" pattern every prior milestone piece used.
 *
 * <p>The restart is simulated exactly as it would happen for real: one {@link SqliteDatabase}
 * is opened, written to, and closed; then a completely new {@link SqliteDatabase} instance is
 * opened against the same data directory, with no in-memory state carried over from the first
 * one — anything it knows has to have come from disk.
 *
 * <p>Found a real bug writing this the first time: {@code file_chunk_state} has a foreign key
 * on {@code file_transfers}, so {@code markChunkReceived} requires {@code saveFileMetadata} to
 * have been called first for that transfer. {@code saveFileMetadata} had to become an upsert
 * (see {@code SqliteStorageService}) because a resumed transfer legitimately calls it again for
 * a {@code transferId} that's already stored.
 */
public class ChunkResumeDemoMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("p2p-chat-chunk-resume-demo");
        System.out.println("Using a fresh temp data dir for this demo: " + dataDir);
        System.out.println();

        String transferId = "t_resume_demo";
        int totalChunks = 10;

        System.out.println("--- \"Run 1\": receive some chunks, then stop (simulating a crash). ---");
        try (SqliteDatabase db1 = SqliteDatabase.openOrCreate(dataDir)) {
            StorageService storage1 = new SqliteStorageService(db1);

            FileTransfer transfer = new FileTransfer(
                    transferId, "c_demo", "resume-demo.bin", 1_000_000L,
                    "deadbeef".repeat(8), 100_000, totalChunks, TransferState.IN_PROGRESS, null,
                    System.currentTimeMillis());
            storage1.saveFileMetadata(transfer);

            List<Integer> missingBeforeAnything = storage1.missingChunks(transferId, totalChunks);
            check("Before anything received, all " + totalChunks + " chunks are missing",
                    missingBeforeAnything.size() == totalChunks && missingBeforeAnything.get(0) == 0);

            for (int i : new int[]{0, 1, 2, 5, 7}) {
                storage1.markChunkReceived(transferId, i);
            }
            storage1.markChunkReceived(transferId, 0); // idempotency: re-marking must not throw or duplicate
            storage1.markChunkReceived(transferId, 0);

            List<Integer> missingMidway = storage1.missingChunks(transferId, totalChunks);
            check("After receiving chunks 0,1,2,5,7: exactly [3,4,6,8,9] are missing",
                    missingMidway.equals(List.of(3, 4, 6, 8, 9)));
        }
        System.out.println("(SqliteDatabase closed here \u2014 this is the simulated crash/restart boundary.)");
        System.out.println();

        System.out.println("--- \"Run 2\": a brand new process, same data dir, no memory of Run 1. ---");
        try (SqliteDatabase db2 = SqliteDatabase.openOrCreate(dataDir)) {
            StorageService storage2 = new SqliteStorageService(db2);

            List<Integer> missingAfterRestart = storage2.missingChunks(transferId, totalChunks);
            check("After the simulated restart, still exactly [3,4,6,8,9] are missing (not all 10)",
                    missingAfterRestart.equals(List.of(3, 4, 6, 8, 9)));

            FileTransfer sameTransferAgain = new FileTransfer(
                    transferId, "c_demo", "resume-demo.bin", 1_000_000L,
                    "deadbeef".repeat(8), 100_000, totalChunks, TransferState.IN_PROGRESS, null,
                    System.currentTimeMillis());
            storage2.saveFileMetadata(sameTransferAgain);
            check("Re-saving metadata for an already-known transferId (a resumed offer) does not throw", true);

            for (int i : missingAfterRestart) {
                storage2.markChunkReceived(transferId, i);
            }
            check("After receiving the rest, nothing is missing", storage2.missingChunks(transferId, totalChunks).isEmpty());

            FileTransfer otherTransfer = new FileTransfer(
                    "t_completely_different", "c_demo", "other.bin", 300L,
                    "cafebabe".repeat(8), 100, 3, TransferState.OFFERED, null, System.currentTimeMillis());
            storage2.saveFileMetadata(otherTransfer);
            check("A different transferId is unaffected by the first transfer's state",
                    storage2.missingChunks("t_completely_different", 3).equals(List.of(0, 1, 2)));
        }

        try (SqliteDatabase db3 = SqliteDatabase.openOrCreate(dataDir)) {
            check("A third open of the same (already-migrated) database succeeds without error", true);
        }

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.out.println();
        System.out.println(failed == 0
                ? "M4d CONFIRMED: chunk-level resume survives a simulated process restart, correctly reports missing chunks, is idempotent, and does not leak state across transfers."
                : "M4d FAILED: see the [FAIL] lines above.");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + description);
        } else {
            failed++;
            System.out.println("[FAIL] " + description);
        }
    }
}
