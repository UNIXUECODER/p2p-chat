package com.p2pchat.relay;

import com.p2pchat.discovery.DiscoveryRecordCodec;
import com.p2pchat.discovery.DiscoveryRecordException;
import com.p2pchat.discovery.SignedDiscoveryRecord;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.model.PeerId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The relay server's discovery logic: stores each publisher's record (opaque bytes — this
 * module doesn't need to know or care what's inside; that's the publishing peer's concern) and
 * answers lookups against it.
 *
 * <p><b>Expiry (M6f):</b> {@code onLookup} best-effort peeks at a stored record's
 * {@code expiresAt} field — via {@link DiscoveryRecordCodec#decodeUnverified}, never
 * {@code verifyAndDecode} — and withholds it once expired, so a signed record's expiry claim
 * is actually enforced somewhere rather than existing only as an unread field. This is
 * deliberately <b>not</b> a security check: the relay is not this system's trust boundary (a
 * malicious relay operator could trivially skip it and serve stale or tampered bytes anyway),
 * so verifying signatures here would be security theater, not a real guarantee. It's hygiene —
 * closer in spirit to RelayRegistry's own long-flagged "no deregistration on disconnect" gap
 * (M3a) than to anything cryptographic. The actual, load-bearing verification only happens
 * client-side, in the peer that's looking a record up — see {@code DiscoveryRecordCodec}.
 *
 * <p>Records that don't decode as a {@code DiscoveryRecordV2} at all (malformed, truncated, or
 * simply an older/different payload shape — e.g. the plain opaque bytes {@code PublishRecordMain}
 * still publishes) are served exactly as before: expiry enforcement only applies to payloads
 * that are actually well-formed V2 records, so this stays fully backward compatible with
 * anything published before this milestone.
 */
public class DiscoveryRegistry implements DiscoveryRequestHandler {

    private final Map<String, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public void onPublish(PeerId publisher, byte[] payload) {
        records.put(publisher.toString(), payload);
        System.out.println("[discovery] published: " + publisher + " (" + payload.length + " bytes, "
                + records.size() + " record(s) on file)");
    }

    @Override
    public byte[] onLookup(String targetPeerId) {
        byte[] result = records.get(targetPeerId);
        if (result != null && isExpiredV2Record(result)) {
            System.out.println("[discovery] lookup for " + targetPeerId + ": found but expired, withholding");
            return null;
        }
        System.out.println("[discovery] lookup for " + targetPeerId + ": " + (result != null ? "found" : "not found"));
        return result;
    }

    /**
     * True only for payloads that decode cleanly as a DiscoveryRecordV2 AND whose expiresAt has
     * passed. Anything that fails to decode as V2 is treated as not-expired (i.e. not this
     * registry's business) rather than rejected — see the class javadoc for why.
     */
    private static boolean isExpiredV2Record(byte[] payload) {
        try {
            SignedDiscoveryRecord signed = DiscoveryRecordCodec.decodeUnverified(payload);
            return signed.record().isExpired(System.currentTimeMillis());
        } catch (DiscoveryRecordException notAV2Record) {
            return false;
        }
    }
}
