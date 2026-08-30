package com.p2pchat.storage;

import com.p2pchat.model.PeerId;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.Conversation;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.FileTransfer;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.Pagination;
import com.p2pchat.storage.model.PeerRoute;
import com.p2pchat.storage.model.TransferState;

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
 *
 * <p><b>M5d update:</b> {@link #hasMessage}, {@link #updateDeliveryState}, and
 * {@link #markMessagesReadUpTo} added — the storage-layer half of "message-id dedup + delivery/
 * read receipt state transitions" (see the M5d section of README.md). Deliberately three narrow,
 * targeted methods rather than one general "patch a message" method, matching this interface's
 * existing convention (see {@link #markChunkReceived}) of adding exactly the operation a real
 * caller needs, not a broader one guessed at in advance.
 *
 * <p><b>M6g-1 update:</b> {@link #listConversations}, {@link #listContacts},
 * {@link #getConversation}, and {@link #getContact} added — the read-side gap identified in
 * {@code docs/M6g-gap-analysis-and-plan.md §1.1/§1.3}: every prior milestone only ever needed to
 * write contacts/conversations, never list or look one up by id, so those methods never existed.
 * The JSON-RPC surface (M6g-4) needs all four directly (`contacts.list`, `conversations.list`),
 * and {@code ContactService} (M6g-2) needs {@link #getContact} specifically to make
 * {@code contacts.add} idempotent — see that class's own Javadoc once it exists.
 *
 * <p><b>M6g-2 update:</b> {@link #upsertPeerRoute}, {@link #getPeerRoute}, and
 * {@link #listPeerRoutes} added — the persistence half of the peer routing table
 * ({@code docs/M6g-gap-analysis-and-plan.md §2.3}). {@code PeerRoute} itself lives in {@code
 * core-storage.model} alongside {@link Contact}/{@link Conversation}, not in {@code node-daemon}
 * as that section first sketched — see {@code PeerRoute}'s own Javadoc for why that's a better
 * fit for what turned out to be a plain persisted row, and {@code node-daemon.PeerRoutingTable}
 * for the daemon-level class that actually still lives where the plan put it.
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
     * M6g-3: transitions a transfer's {@code state} (OFFERED → ACCEPTED → IN_PROGRESS →
     * COMPLETED/FAILED, per {@code TransferState}). A genuinely separate method from {@link
     * #saveFileMetadata} on purpose, not an oversight of that method's own contract — {@code
     * saveFileMetadata} is deliberately {@code INSERT OR IGNORE} (see its own Javadoc: "existing
     * rows are left untouched"), which makes it structurally incapable of ever changing a stored
     * row's state once the row exists, no matter what {@code TransferState} value the caller
     * passes in on a later call. This method exists for exactly the update that one can't do —
     * same "narrow, targeted method matching a real caller's need" convention as {@link
     * #updateDeliveryState}, which this mirrors field-for-field on the sibling {@code messages}
     * table. A no-op, not an error, if {@code transferId} doesn't exist — matching {@code
     * updateDeliveryState}'s own established behavior for an unknown id.
     */
    void updateTransferState(String transferId, TransferState newState);

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

    /**
     * M5d: returns true if a message with this {@code messageId} has already been persisted.
     * Callers receiving an inbound {@code ChatMessagePayload} check this <i>before</i> calling
     * {@link #saveMessage} — docs/architecture-spec.md §15's "Duplicate message delivery (e.g.
     * via both a direct reconnect and a queued relay copy) → dedup on message_id at the storage
     * layer before it ever reaches the UI."
     *
     * <p>Deliberately a query the caller consults, not a behavior change to {@link #saveMessage}
     * itself. {@link #saveMessage} stays a plain insert (see its own Javadoc) so a genuine bug
     * that tries to reuse a {@code messageId} for two different messages fails loudly with a
     * primary-key violation, instead of an {@code INSERT OR IGNORE} silently discarding the
     * second one and masking the bug — the same "fail loud on a real bug, upsert only for a
     * legitimately-repeatable call" distinction {@link #saveFileMetadata} vs. this interface's
     * plain-insert methods already draws.
     */
    boolean hasMessage(String messageId);

    /**
     * M5d: updates the {@code delivery_state} of one existing message, identified by
     * {@code messageId}. Two real callers: an inbound {@code DeliveryReceiptPayload}
     * (state → {@code DELIVERED}, for a message this node originally sent), and a local
     * "mark read" action on a message this device received (state → {@code READ}). A no-op
     * (does not throw) if {@code messageId} doesn't exist — a receipt racing ahead of, or
     * arriving after, a local dedup/cleanup path shouldn't be able to crash the caller for
     * something this secondary to the main flow.
     */
    void updateDeliveryState(String messageId, DeliveryState newState);

    /**
     * M5d: marks every message in {@code conversationId} <b>authored by {@code senderPeerId}</b>
     * with {@code hlc_timestamp <= readUpToHlcTimestamp} as {@code READ}. The handler for an
     * inbound {@code ReadReceiptPayload}: the receipt's sender is reporting they've read
     * everything up to a point in the conversation, so this updates the delivery_state of
     * <i>this node's own outgoing messages</i> in that range — never the receipt-sender's own
     * messages, which this node has no authority to mark on their behalf. {@code senderPeerId}
     * is therefore always this node's own {@code PeerId} at the call site, not the receipt's
     * remote sender.
     *
     * <p>{@code hlc_timestamp} comparison is plain SQLite {@code TEXT} ordering
     * ({@code <=} on the column), which is safe here specifically because
     * {@code core-messaging.HlcTimestamp#toString()}'s fixed-width zero-padded encoding makes
     * string order agree with causal order exactly (see that class's own Javadoc and
     * {@code HlcTimestampTest.stringOrderingMatchesCompareTo}) — not a coincidence this method
     * relies on without justification.
     */
    void markMessagesReadUpTo(String conversationId, PeerId senderPeerId, String readUpToHlcTimestamp);

    /**
     * M6g-1: every conversation, most-recently-active first. "Active" means the {@code
     * hlc_timestamp} of the conversation's most recent message if it has any messages yet, or
     * {@code created_at} otherwise — a freshly-seeded conversation with no messages (see
     * {@link #saveConversation}'s upsert note on why that's a real, common state) still needs a
     * deterministic, sensible position in the list rather than sorting arbitrarily or being
     * excluded. Two conversations with no messages sort by {@code created_at} relative to each
     * other; a conversation with messages always sorts ahead of one without, regardless of when
     * either was created, since "most recently active" is about activity, not age.
     */
    List<Conversation> listConversations();

    /** M6g-1: every contact, alphabetical by display name (case-insensitive), then by {@code peerId} to break ties deterministically. */
    List<Contact> listContacts();

    /** M6g-1: a single conversation by id, or {@code null} if none exists — never throws for a missing id. */
    Conversation getConversation(String conversationId);

    /** M6g-1: a single contact by peer id, or {@code null} if none exists — never throws for a missing id. */
    Contact getContact(PeerId peerId);

    /**
     * M6g-2: records (or refreshes) what this daemon currently knows about reaching {@code
     * observed.peerId()}. Merge semantics, not a blind overwrite: any field left {@code null} on
     * {@code observed} preserves whatever this daemon already had stored for that field, rather
     * than erasing it. Necessary because the real call sites — a discovery lookup, an inbound
     * message's {@code senderAddress}, {@code contacts.add}, a relay registration — each learn a
     * different subset of a peer's route at different times; a blind overwrite from any one of
     * them would silently discard whatever the others had already contributed. {@code lastSeen}
     * is the one exception — always taken from {@code observed}, never preserved, since every
     * call to this method represents a real, fresh observation worth recording as such.
     *
     * <p>The first call for a given {@code peerId} has nothing to merge against, so it behaves
     * as a plain insert of exactly what {@code observed} contains, {@code null}s included.
     *
     * @return the row as it exists after the merge — saves a caller a follow-up {@link
     *         #getPeerRoute} just to see the result.
     */
    PeerRoute upsertPeerRoute(PeerRoute observed);

    /** M6g-2: a single peer route by peer id, or {@code null} if this daemon has never observed one. */
    PeerRoute getPeerRoute(PeerId peerId);

    /** M6g-2: every known peer route, most-recently-observed first. */
    List<PeerRoute> listPeerRoutes();

    /** Runs {@code work} inside a single SQLite transaction, committing on normal return and rolling back if {@code work} throws. */
    <T> T runInTransaction(Supplier<T> work);
}
