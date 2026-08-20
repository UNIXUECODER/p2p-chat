-- V002__signal_store.sql
-- Persistent storage for Signal Protocol sessions, pre-keys, and remote identity keys.
--
-- Backs SqliteSignalProtocolStore (node-daemon), not core-storage itself -- core-storage has no
-- libsignal-client dependency and stays that way. This schema is deliberately opaque-blob-shaped
-- (every *_record / identity_key column is exactly what libsignal's own .serialize() returns,
-- round-tripped through the matching byte[]-constructor -- never parsed or inspected here, per
-- Signal's own guidance that store implementations must not interpret record internals).
--
-- Does NOT store this node's own local identity (IdentityKeyPair + registration ID) -- that
-- stays exactly where SignalIdentityVault (M2a) already puts it, in dataDir/signal-identity.key
-- and .reg. Only genuinely dynamic runtime state lives here: pre-keys generated at startup,
-- sessions established at runtime, and remote peers' identity keys learned via saveIdentity().

CREATE TABLE IF NOT EXISTS signal_sessions (
    address         TEXT PRIMARY KEY,  -- "<SignalProtocolAddress.getName()>.<getDeviceId()>"
    session_record  BLOB NOT NULL,     -- SessionRecord.serialize()
    updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS signal_pre_keys (
    prekey_id       INTEGER PRIMARY KEY,
    record          BLOB NOT NULL      -- PreKeyRecord.serialize()
);

CREATE TABLE IF NOT EXISTS signal_signed_pre_keys (
    signed_prekey_id INTEGER PRIMARY KEY,
    record           BLOB NOT NULL     -- SignedPreKeyRecord.serialize()
);

-- `used` is the one deliberate departure from the originally proposed schema (see
-- SqliteSignalProtocolStore's Javadoc for the reasoning): unlike one-time EC pre-keys, Kyber
-- pre-keys in PQXDH are not consumed on first use -- KyberPreKeyStore.markKyberPreKeyUsed(id) is
-- a distinct SPI method from PreKeyStore.removePreKey(id) precisely because the real contract is
-- "flag as used, keep it" rather than "delete it". A column, not a DELETE, is what that method
-- needs to persist.
CREATE TABLE IF NOT EXISTS signal_kyber_pre_keys (
    kyber_prekey_id INTEGER PRIMARY KEY,
    record          BLOB NOT NULL,     -- KyberPreKeyRecord.serialize()
    used            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS signal_identities (
    address         TEXT PRIMARY KEY,  -- same "<name>.<deviceId>" form as signal_sessions
    identity_key    BLOB NOT NULL      -- IdentityKey.serialize()
);
