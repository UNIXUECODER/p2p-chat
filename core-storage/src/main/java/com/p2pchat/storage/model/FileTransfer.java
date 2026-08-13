package com.p2pchat.storage.model;

/**
 * Mirrors the {@code file_transfers} table (docs/architecture-spec.md §9). {@code localPath}
 * and {@code createdAt} are real columns not shown in §4's leaner domain-record sketch; kept
 * here for the same reason as {@code Message.createdAt} — this type exists to be persisted, so
 * it matches what's actually persisted.
 *
 * <p>Deliberately does not include any chunk-level state — {@code file_chunk_state} is a
 * separate table with its own per-chunk rows, and the read/write access pattern a resumable
 * transfer actually needs from that table is M4's design work, not this scaffold's.
 */
public record FileTransfer(
        String transferId,
        String conversationId,
        String fileName,
        long fileSize,
        String fileHash,
        int chunkSize,
        int totalChunks,
        TransferState state,
        String localPath,
        long createdAt
) {
}
