package com.p2pchat.storage;

import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.Pagination;

import java.util.List;
import java.util.function.Supplier;

/**
 * The persistence contract sketched in docs/architecture-spec.md §5 — the only core-* module
 * that touches SQLite directly. Backed by {@link SqliteStorageService}; schema defined in
 * docs/architecture-spec.md §9 and applied via {@link MigrationRunner}.
 *
 * <p><b>Scope note (M3d):</b> originally scoped to match spec §5 exactly — five methods,
 * nothing more — deliberately deferring chunk-level and conversation/group methods until a
 * real consumer needed them rather than guessing at a shape in advance.
 *
 * <p><b>M4d update:</b> {@link #markChunkReceived} / {@link #missingChunks} added now that
 * {@code file_chunk_state} has a real consumer (M4c's file transfer).
 *
 * <p><b>M4e update:</b> {@link #saveConversation} added to close the gap M4d flagged and left
 * open — {@code messages.conversation_id REFERENCES conversations}, but nothing could create a
 * {@code conversations} row, so {@link #saveMessage} always failed with a foreign-key violation
 * for any conversation not created by some out-of-band means. See {@link Conversation}'s Javadoc
 * for why this is intentionally narrower than full conversation/membership management, which is
 * still M5/M8's job.
 */
public interface StorageService {

    /** Inserts a message. Callers are responsible for generating a unique {@code messageId}; this is a plain insert, not an upsert. */
    void saveMessage(Message message);

    /** Returns messages in a conversation, oldest first, after {@code page}'s cursor (or from the start, if null/blank), up to {@code page}'s limit. */
    List<Message> queryMessages(String conversationId, Pagination page);

    /** Inserts a contact. */
    void saveContact(Contact contact);

    /**
     * Inserts file-transfer metadata. Unlike {@link #saveMessage}/{@link #saveContact}, this IS
     * safe to call more than once for the same {@code transferId} (existing rows are left
     * untouched, not overwritten) — {@code file_chunk_state} has a foreign key on this table
     * (see V001__init.sql), so a resumed transfer legitimately re-establishes this row before
     * {@link #markChunkReceived} can be called.
     */
    void saveFileMetadata(FileTransfer transfer);

    /**
     * Inserts a conversation. Safe to call more than once for the same {@code conversationId}
     * (existing rows are left untouched, not overwritten) — same upsert reasoning as
     * {@link #saveFileMetadata}: a 1:1 conversation with a given contact is naturally
     * re-derived/re-seeded each time a message is sent to them, not created exactly once up
     * front, so callers must be free to call this unconditionally before every
     * {@link #saveMessage} without checking "does this conversation already exist" themselves.
     */
    void saveConversation(Conversation conversation);

    /**
     * Records that a chunk has been received for a transfer. Idempotent — marking an
     * already-received chunk again is a no-op, not an error. Deliberately does not store the
     * chunk's bytes; {@code file_chunk_state} (docs/architecture-spec.md §9) only ever tracked a
     * boolean per chunk. Durably keeping the actual decrypted bytes so a restart has something
     * to resume <i>into</i>, not just something to avoid re-requesting, is the caller's job —
     * see {@code FileReceiverMain}, which writes each chunk directly into the output file at its
     * correct byte offset before calling this.
     */
    void markChunkReceived(String transferId, int chunkIndex);

    /** Returns the indices in {@code [0, totalChunks)} NOT yet marked received for this transfer — empty means the transfer is complete. */
    List<Integer> missingChunks(String transferId, int totalChunks);

    /** Runs {@code work} inside a single SQLite transaction, committing on normal return and rolling back if {@code work} throws. */
    <T> T runInTransaction(Supplier<T> work);
}
