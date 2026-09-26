-- Track A-3 (pre-m6h-hardening-plan.md): durable store-and-forward queue for a FORWARD frame
-- the relay can't deliver immediately because its target isn't currently connected.
--
-- Deliberately separate from core-storage's own schema: this is a completely independent
-- database file (relay-spool.sqlite), applied by its own MigrationRunner instance, tracked in
-- its own schema_migrations table. See SqliteRelaySpool's own Javadoc for why sender_peer_id is
-- carried alongside the payload rather than inferred later, and RelaySpool's own Javadoc for why
-- there is no "delivered" flag or foreign key to any per-connection state -- a spooled message's
-- only two states are "still queued" (a row exists) and "gone" (it doesn't), nothing in between.
CREATE TABLE spooled_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    target_peer_id TEXT NOT NULL,
    sender_peer_id TEXT NOT NULL,
    payload BLOB NOT NULL,
    enqueued_at INTEGER NOT NULL
);

-- Every real query against this table filters by target_peer_id and orders by id (insertion
-- order, i.e. FIFO) -- see RelaySpool#fetchForTarget. Track A-3's later TTL purge (not this
-- migration) will scan by enqueued_at directly rather than through this index -- a second index
-- for that is deferred until the purge is actually written, so its real query shape is known
-- rather than guessed at here.
CREATE INDEX idx_spooled_messages_target ON spooled_messages (target_peer_id, id);
