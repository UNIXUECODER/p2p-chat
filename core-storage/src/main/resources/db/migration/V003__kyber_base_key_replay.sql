-- V003__kyber_base_key_replay.sql
-- Persistent tracking for Kyber pre-key base-key reuse detection.
--
-- Added after review found SqliteSignalProtocolStore.markKyberPreKeyUsed's first draft tracked
-- seen base keys in a plain in-memory java.util.Map -- the exact problem this whole milestone
-- (M6e-1) exists to solve, reappearing inside M6e-1 itself: that protection would silently reset
-- on every daemon restart. A composite-key table does the persistence AND the byte-value
-- comparison for free, via SQL's own PRIMARY KEY uniqueness rather than a Java Set<ECPublicKey>
-- relying on that type having value-based equals()/hashCode(), which nothing confirms it does.
--
-- New migration, not an edit to V002 -- see architecture-spec.md §9: "never hand-edit the schema
-- in place once shipped." V002 is already applied against real data by this point.

CREATE TABLE IF NOT EXISTS signal_kyber_base_keys_seen (
    kyber_prekey_id  INTEGER NOT NULL,
    signed_prekey_id INTEGER NOT NULL,
    base_key         BLOB NOT NULL,      -- ECPublicKey.serialize() -- opaque, same convention as V002
    PRIMARY KEY (kyber_prekey_id, signed_prekey_id, base_key)
);
