package com.p2pchat.daemon.crypto;

import com.p2pchat.storage.SqliteDatabase;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * M6e-1: a real, persistent {@link SignalProtocolStore}, backed by the same SQLite database
 * {@code SqliteStorageService} already uses (schema: {@code V002__signal_store.sql}).
 *
 * <p><b>Why this exists.</b> Every demo Main through M5e uses libsignal's own
 * {@code InMemorySignalProtocolStore} — correct for a proof, wrong for a daemon: every session,
 * every one-time pre-key, and every remote peer's trusted identity would evaporate on restart,
 * forcing a fresh PQXDH handshake per peer per restart and defeating the actual security purpose
 * of the Double Ratchet's forward secrecy (a session that can never persist gains nothing from
 * ratcheting). {@code LibsignalSecureSessionService}'s constructor already takes the
 * {@code SignalProtocolStore} <i>interface</i>, not the concrete in-memory class — this slots in
 * with zero changes to core-crypto.
 *
 * <p><b>Where the local identity comes from.</b> This class does not generate or store this
 * node's own {@code IdentityKeyPair}/registration ID — those are injected via the constructor,
 * still sourced from {@code SignalIdentityVault} (M2a) exactly as every demo Main already does.
 * Only genuinely dynamic state — pre-keys, sessions, and <i>remote</i> peers' identity keys — is
 * persisted here.
 *
 * <p><b>Every record is treated as an opaque blob</b> — {@code record.serialize()} in, the
 * matching {@code byte[]}-constructor out, exactly as Signal's own developer documentation
 * describes a store implementation's responsibility. Nothing in this class inspects a ratchet's
 * internal fields.
 *
 * <p><b>Deliberate schema departure from the first draft (see {@code V002__signal_store.sql}):
 * </b> Kyber pre-keys get a {@code used} flag, not a {@code DELETE}, unlike one-time EC pre-keys
 * — {@link #markKyberPreKeyUsed} and {@link #removePreKey} are two different SPI methods on two
 * different sub-interfaces precisely because PQXDH's Kyber pre-keys are last-resort/reusable,
 * not strictly single-use.
 *
 * <p><b>Verification status.</b> This sandbox cannot resolve {@code org.signal:libsignal-client}
 * (Maven Central is blocked here), so the first draft of this class was hand-traced against
 * every libsignal type/method this project's own already-compiling M2–M5 code proves, then
 * compiled and run for real against a fetched {@code sqlite-jdbc} driver for the JDBC/SQL half
 * only. Two real signature mismatches surfaced only once compiled against the actual
 * {@code libsignal-client} 0.94.0 jar, both since fixed here: {@link #saveIdentity} returns
 * {@code IdentityChange}, not {@code boolean}; {@link #markKyberPreKeyUsed} takes
 * {@code (kyberPreKeyId, signedPreKeyId, ECPublicKey baseKey)} and can throw
 * {@code ReusedBaseKeyException}, not a bare {@code (kyberPreKeyId)}. The fix for the latter
 * also corrected a real bug the hand-traced first draft introduced: base-key replay tracking
 * was a plain in-memory field, silently losing its protection on every restart — the exact
 * problem this class exists to solve, reappearing inside itself. See {@code
 * V003__kyber_base_key_replay.sql} and this method's own comments. The one part with still no
 * existing usage anywhere in this project to ground against, and not yet exercised by a real
 * compile, is {@link org.signal.libsignal.protocol.groups.state.SenderKeyStore} (group
 * messaging, M8's concern) — if a future real build reports a mismatch, it is most likely here;
 * nothing else in this class depends on that being exactly right.
 *
 * <p><b>Thread safety:</b> none, deliberately — see {@link SynchronizedSignalProtocolStore},
 * which wraps any {@code SignalProtocolStore} (this one included) with the actual guarantee M6e
 * needs for concurrent peer sessions. Keeping that orthogonal here means this class's own
 * correctness (the part that matters most and is hardest to verify) isn't tangled up with
 * locking.
 */
public final class SqliteSignalProtocolStore implements SignalProtocolStore {

    private final Connection connection;
    private final IdentityKeyPair localIdentityKeyPair;
    private final int localRegistrationId;

    public SqliteSignalProtocolStore(SqliteDatabase database, IdentityKeyPair localIdentityKeyPair,
                                      int localRegistrationId) {
        this.connection = database.connection();
        this.localIdentityKeyPair = localIdentityKeyPair;
        this.localRegistrationId = localRegistrationId;
    }

    // ---------------------------------------------------------------- IdentityKeyStore

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        return localIdentityKeyPair;
    }

    @Override
    public int getLocalRegistrationId() {
        return localRegistrationId;
    }

    @Override
    public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        IdentityKey existing = getIdentity(address);
        byte[] newBytes = identityKey.serialize();

        if (existing == null) {
            runUpdate("INSERT INTO signal_identities (address, identity_key) VALUES (?, ?)",
                    statement -> {
                        statement.setString(1, addressKey(address));
                        statement.setBytes(2, newBytes);
                    });
            return IdentityChange.NEW_OR_UNCHANGED; // first time seeing this address -- nothing replaced
        }

        if (Arrays.equals(existing.serialize(), newBytes)) {
            return IdentityChange.NEW_OR_UNCHANGED; // identical to what's already trusted -- no-op, not a change
        }

        // A genuinely different identity for an address we've seen before -- this is the actual
        // "safety number changed" signal a real client would surface to the user. M6e-1 doesn't
        // build that UI; it only needs to report the change honestly so a future caller can.
        runUpdate("UPDATE signal_identities SET identity_key = ? WHERE address = ?", statement -> {
            statement.setBytes(1, newBytes);
            statement.setString(2, addressKey(address));
        });
        return IdentityChange.REPLACED_EXISTING;
    }

    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
        IdentityKey existing = getIdentity(address);
        // Trust-on-first-use: no prior identity recorded for this address at all -- the standard
        // default policy, matching Signal's own. Once *something* is recorded, it must match --
        // a different key for an address we've already pinned an identity for is exactly the
        // MITM-key-swap case identity pinning exists to catch, on either direction.
        return existing == null || Arrays.equals(existing.serialize(), identityKey.serialize());
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        return querySingle("SELECT identity_key FROM signal_identities WHERE address = ?",
                addressKey(address), bytes -> {
                    try {
                        return new IdentityKey(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException("Corrupt identity_key row for " + addressKey(address), e);
                    }
                });
    }

    // ---------------------------------------------------------------- PreKeyStore

    @Override
    public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        PreKeyRecord record = querySingle("SELECT record FROM signal_pre_keys WHERE prekey_id = ?",
                preKeyId, PreKeyRecord::new);
        if (record == null) {
            throw new InvalidKeyIdException("No such pre-key: " + preKeyId);
        }
        return record;
    }

    @Override
    public void storePreKey(int preKeyId, PreKeyRecord record) {
        runUpdate("INSERT OR REPLACE INTO signal_pre_keys (prekey_id, record) VALUES (?, ?)", statement -> {
            statement.setInt(1, preKeyId);
            statement.setBytes(2, record.serialize());
        });
    }

    @Override
    public boolean containsPreKey(int preKeyId) {
        return exists("SELECT 1 FROM signal_pre_keys WHERE prekey_id = ?", preKeyId);
    }

    @Override
    public void removePreKey(int preKeyId) {
        // The actual point of this whole class, per the M6 roadmap: a consumed one-time pre-key
        // is physically deleted, not just marked used, so it can never be replayed even from a
        // compromised copy of this database file after the fact -- forward secrecy holding
        // across restarts depends on this being a real DELETE, not a soft flag.
        runUpdate("DELETE FROM signal_pre_keys WHERE prekey_id = ?", statement -> statement.setInt(1, preKeyId));
    }

    // ---------------------------------------------------------------- SignedPreKeyStore

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
        SignedPreKeyRecord record = querySingle("SELECT record FROM signal_signed_pre_keys WHERE signed_prekey_id = ?",
                signedPreKeyId, SignedPreKeyRecord::new);
        if (record == null) {
            throw new InvalidKeyIdException("No such signed pre-key: " + signedPreKeyId);
        }
        return record;
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        return queryList("SELECT record FROM signal_signed_pre_keys", SignedPreKeyRecord::new);
    }

    @Override
    public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        runUpdate("INSERT OR REPLACE INTO signal_signed_pre_keys (signed_prekey_id, record) VALUES (?, ?)",
                statement -> {
                    statement.setInt(1, signedPreKeyId);
                    statement.setBytes(2, record.serialize());
                });
    }

    @Override
    public boolean containsSignedPreKey(int signedPreKeyId) {
        return exists("SELECT 1 FROM signal_signed_pre_keys WHERE signed_prekey_id = ?", signedPreKeyId);
    }

    @Override
    public void removeSignedPreKey(int signedPreKeyId) {
        runUpdate("DELETE FROM signal_signed_pre_keys WHERE signed_prekey_id = ?",
                statement -> statement.setInt(1, signedPreKeyId));
    }

    // ---------------------------------------------------------------- KyberPreKeyStore

    @Override
    public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
        KyberPreKeyRecord record = querySingle("SELECT record FROM signal_kyber_pre_keys WHERE kyber_prekey_id = ?",
                kyberPreKeyId, KyberPreKeyRecord::new);
        if (record == null) {
            throw new InvalidKeyIdException("No such Kyber pre-key: " + kyberPreKeyId);
        }
        return record;
    }

    @Override
    public List<KyberPreKeyRecord> loadKyberPreKeys() {
        return queryList("SELECT record FROM signal_kyber_pre_keys", KyberPreKeyRecord::new);
    }

    @Override
    public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        runUpdate("INSERT OR REPLACE INTO signal_kyber_pre_keys (kyber_prekey_id, record, used) VALUES (?, ?, 0)",
                statement -> {
                    statement.setInt(1, kyberPreKeyId);
                    statement.setBytes(2, record.serialize());
                });
    }

    @Override
    public boolean containsKyberPreKey(int kyberPreKeyId) {
        return exists("SELECT 1 FROM signal_kyber_pre_keys WHERE kyber_prekey_id = ?", kyberPreKeyId);
    }

    @Override
    public void markKyberPreKeyUsed(int kyberPreKeyId, int signedPreKeyId, ECPublicKey baseKey)
            throws ReusedBaseKeyException {
        // Check first, side effects second -- the first draft ran the UPDATE unconditionally
        // before this check could throw, so a rejected (replayed) call still left a committed
        // write behind. Harmless in that specific case (re-flagging an already-used row is
        // idempotent) but the wrong order on principle: a rejection shouldn't have side effects.
        if (baseKeySeen(kyberPreKeyId, signedPreKeyId, baseKey.serialize())) {
            throw new ReusedBaseKeyException();
        }

        // Not a DELETE -- see this class's Javadoc and V002's schema comment. Real PQXDH Kyber
        // pre-keys are last-resort/reusable, unlike one-time EC pre-keys.
        runUpdate("UPDATE signal_kyber_pre_keys SET used = 1 WHERE kyber_prekey_id = ?",
                statement -> statement.setInt(1, kyberPreKeyId));

        // V003, not an in-memory Set -- see that migration's comment for why the first draft's
        // java.util.Map was the exact problem this whole class exists to fix, reappearing inside
        // it. Check-then-insert, not an atomic constraint-violation catch, to stay driver-
        // agnostic (plain java.sql.*, no sqlite-jdbc-specific exception types) like every other
        // method here -- safe only because every real caller goes through
        // SynchronizedSignalProtocolStore's single monitor, unlike the rest of this class, which
        // doesn't depend on that. Worth being explicit that this one method leans on it.
        runUpdate("INSERT INTO signal_kyber_base_keys_seen (kyber_prekey_id, signed_prekey_id, base_key) " +
                "VALUES (?, ?, ?)", statement -> {
            statement.setInt(1, kyberPreKeyId);
            statement.setInt(2, signedPreKeyId);
            statement.setBytes(3, baseKey.serialize());
        });
    }

    private boolean baseKeySeen(int kyberPreKeyId, int signedPreKeyId, byte[] baseKeyBytes) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM signal_kyber_base_keys_seen " +
                        "WHERE kyber_prekey_id = ? AND signed_prekey_id = ? AND base_key = ?")) {
            statement.setInt(1, kyberPreKeyId);
            statement.setInt(2, signedPreKeyId);
            statement.setBytes(3, baseKeyBytes);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: base-key replay check", e);
        }
    }

    // ---------------------------------------------------------------- SessionStore

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        SessionRecord record = querySingle("SELECT session_record FROM signal_sessions WHERE address = ?",
                addressKey(address), SessionRecord::new);
        // libsignal's own SessionBuilder/SessionCipher call this as their starting point for
        // every session, including the very first message ever exchanged with a brand-new peer
        // -- returning null or throwing here would break session establishment outright. A fresh,
        // empty SessionRecord (no session yet, not an error) is the correct "not found" answer.
        return record != null ? record : new SessionRecord();
    }

    @Override
    public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        List<SessionRecord> records = new ArrayList<>(addresses.size());
        for (SignalProtocolAddress address : addresses) {
            SessionRecord record = querySingle("SELECT session_record FROM signal_sessions WHERE address = ?",
                    addressKey(address), SessionRecord::new);
            if (record == null) {
                // Unlike loadSession, this method's contract is "these must already exist" --
                // used where a missing session is a genuine error, not a fresh-start signal.
                throw new NoSessionException("No session for " + addressKey(address));
            }
            records.add(record);
        }
        return records;
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        List<Integer> deviceIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT address FROM signal_sessions WHERE address LIKE ?")) {
            statement.setString(1, name + ".%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String address = resultSet.getString("address");
                    int lastDot = address.lastIndexOf('.');
                    deviceIds.add(Integer.parseInt(address.substring(lastDot + 1)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query sub-device sessions for " + name, e);
        }
        return deviceIds;
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        runUpdate("INSERT OR REPLACE INTO signal_sessions (address, session_record, updated_at) VALUES (?, ?, ?)",
                statement -> {
                    statement.setString(1, addressKey(address));
                    statement.setBytes(2, record.serialize());
                    statement.setLong(3, System.currentTimeMillis());
                });
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        return exists("SELECT 1 FROM signal_sessions WHERE address = ?", addressKey(address));
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        runUpdate("DELETE FROM signal_sessions WHERE address = ?",
                statement -> statement.setString(1, addressKey(address)));
    }

    @Override
    public void deleteAllSessions(String name) {
        runUpdate("DELETE FROM signal_sessions WHERE address LIKE ?", statement -> statement.setString(1, name + ".%"));
    }

    // ---------------------------------------------------------------- SenderKeyStore
    // Group messaging is M8's scope -- nothing through M6 has a real SenderKeyRecord to store or
    // load, and this is the one sub-interface with no existing usage anywhere in this project to
    // hand-trace the exact real signature against (see this class's Javadoc). Rather than guess
    // at a schema/serialization shape for a feature two milestones away, this fails loudly and
    // specifically instead of silently doing nothing -- a real M8 caller will get an unambiguous
    // "not implemented yet" instead of session state quietly not persisting.

    @Override
    public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        throw new UnsupportedOperationException(
                "Sender-key (group) storage is not implemented -- M8's scope, not M6's");
    }

    @Override
    public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        throw new UnsupportedOperationException(
                "Sender-key (group) storage is not implemented -- M8's scope, not M6's");
    }

    // ---------------------------------------------------------------- JDBC plumbing

    private static String addressKey(SignalProtocolAddress address) {
        return address.getName() + "." + address.getDeviceId();
    }

    private interface RecordDeserializer<T> {
        T deserialize(byte[] bytes) throws Exception;
    }

    private <T> T querySingle(String sql, String key, RecordDeserializer<T> deserializer) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            return extractSingle(statement, deserializer);
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }

    private <T> T querySingle(String sql, int key, RecordDeserializer<T> deserializer) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, key);
            return extractSingle(statement, deserializer);
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }

    private <T> T extractSingle(PreparedStatement statement, RecordDeserializer<T> deserializer) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            byte[] bytes = resultSet.getBytes(1);
            try {
                return deserializer.deserialize(bytes);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize stored record", e);
            }
        }
    }

    private <T> List<T> queryList(String sql, RecordDeserializer<T> deserializer) {
        List<T> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                try {
                    results.add(deserializer.deserialize(resultSet.getBytes(1)));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize stored record", e);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
        return results;
    }

    private boolean exists(String sql, String key) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }

    private boolean exists(String sql, int key) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }

    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private void runUpdate(String sql, StatementBinder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Update failed: " + sql, e);
        }
    }
}
