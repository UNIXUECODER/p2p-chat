-- V004__peer_routes.sql
-- Peer routing table (M6g-2): how to reach a known peer right now, and enough to start a
-- session with them, so an RPC caller (or ContactService) only needs a peer ID -- not raw
-- multiaddrs -- to reach messages.send / SessionManager.sendChatMessage.
--
-- Deliberately does NOT store a "has_session" column -- see PeerRoute's own javadoc for why:
-- that fact lives in the SignalProtocolStore, and a second, unsynchronized copy of it here
-- would go stale the moment a session is established or torn down without this row being told.
--
-- New migration, not an edit to V001/V002/V003 -- same "never hand-edit the schema in place
-- once shipped" rule V003's own header already states.

CREATE TABLE IF NOT EXISTS peer_routes (
    peer_id           TEXT PRIMARY KEY,
    direct_multiaddr  TEXT,
    relay_multiaddr   TEXT,
    display_name      TEXT,
    pre_key_bundle    BLOB,               -- PreKeyBundleCodec-encoded, opaque -- see PeerRoute javadoc
    last_seen         INTEGER NOT NULL
);
