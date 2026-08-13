CREATE TABLE identities (
  peer_id           TEXT PRIMARY KEY,
  display_name      TEXT NOT NULL,
  identity_pubkey   BLOB NOT NULL,
  created_at        INTEGER NOT NULL
);

CREATE TABLE contacts (
  peer_id           TEXT PRIMARY KEY,
  display_name      TEXT,
  verified          INTEGER DEFAULT 0,
  added_at          INTEGER NOT NULL
);

CREATE TABLE conversations (
  conversation_id   TEXT PRIMARY KEY,
  type              TEXT CHECK(type IN ('DIRECT','GROUP')) NOT NULL,
  name              TEXT,
  created_at        INTEGER NOT NULL
);

CREATE TABLE conversation_members (
  conversation_id   TEXT NOT NULL REFERENCES conversations(conversation_id),
  peer_id           TEXT NOT NULL,
  role              TEXT CHECK(role IN ('MEMBER','ADMIN')) DEFAULT 'MEMBER',
  joined_at         INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, peer_id)
);

-- sender_device_id is not present in docs/architecture-spec.md's §9 schema text, but the
-- Message domain record in §4 has a senderDeviceId field, and §13 states every message
-- already carries a device_id. Treated as a spec omission and added here rather than left
-- out — see the M3d section of README.md.
CREATE TABLE messages (
  message_id        TEXT PRIMARY KEY,
  conversation_id   TEXT NOT NULL REFERENCES conversations(conversation_id),
  sender_peer_id    TEXT NOT NULL,
  sender_device_id  TEXT NOT NULL DEFAULT '0',
  hlc_timestamp     TEXT NOT NULL,
  content_type      TEXT NOT NULL,
  plaintext_cache   TEXT,
  delivery_state    TEXT CHECK(delivery_state IN ('SENDING','SENT','DELIVERED','READ','FAILED')),
  created_at        INTEGER NOT NULL
);

CREATE INDEX idx_messages_conv_time ON messages(conversation_id, hlc_timestamp);

CREATE TABLE file_transfers (
  transfer_id       TEXT PRIMARY KEY,
  conversation_id   TEXT NOT NULL,
  file_name         TEXT NOT NULL,
  file_size         INTEGER NOT NULL,
  file_hash         TEXT NOT NULL,
  chunk_size        INTEGER NOT NULL,
  total_chunks      INTEGER NOT NULL,
  state             TEXT CHECK(state IN ('OFFERED','ACCEPTED','IN_PROGRESS','PAUSED','COMPLETED','FAILED','CANCELLED')),
  local_path        TEXT,
  created_at        INTEGER NOT NULL
);

CREATE TABLE file_chunk_state (
  transfer_id       TEXT NOT NULL REFERENCES file_transfers(transfer_id),
  chunk_index       INTEGER NOT NULL,
  received          INTEGER DEFAULT 0,
  PRIMARY KEY (transfer_id, chunk_index)
);

CREATE TABLE crdt_ops_log (
  op_id             TEXT PRIMARY KEY,
  conversation_id   TEXT NOT NULL,
  actor_peer_id     TEXT NOT NULL,
  op_type           TEXT NOT NULL,
  op_payload        TEXT NOT NULL,
  hlc_timestamp     TEXT NOT NULL
);
