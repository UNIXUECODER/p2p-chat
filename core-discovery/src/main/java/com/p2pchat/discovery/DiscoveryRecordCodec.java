package com.p2pchat.discovery;

import com.p2pchat.model.PeerId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire codec for signed discovery records (M6f). Same hand-rolled, length-prefixed binary
 * style as every other wire codec in this project (RelayFrameCodec, DiscoveryFrameCodec,
 * PreKeyBundleCodec) — not JSON. This travels inside {@code DiscoveryFrame}'s opaque payload,
 * the same slot the M3c unsigned records used, so relay-server needed no changes to keep
 * routing it; see {@code DiscoveryRegistry}, which only ever peeks at expiry, never at record
 * contents or signatures.
 *
 * <p>Wire shape:
 * <pre>
 * [1 byte]  format marker (0x02 for this version — see FORMAT_MARKER)
 * ---- canonical unsigned bytes: encodeCanonicalUnsigned(), what actually gets signed ----
 * [4 bytes] address count
 *   repeated: [4 bytes length][UTF-8 bytes]
 * [1 byte]  has-preKeyBundle flag
 *   if 1:   [4 bytes length][bytes]
 * [1 byte]  has-relayMultiaddr flag
 *   if 1:   [4 bytes length][UTF-8 bytes]
 * [8 bytes] expiresAt (long, epoch millis)
 * ---- end canonical unsigned bytes ----
 * [4 bytes] public key length (always 32) [32 bytes: raw Ed25519 public key]
 * [4 bytes] signature length (always 64)  [64 bytes: raw Ed25519 signature]
 * </pre>
 *
 * <p>The marker byte sits outside the signed region on purpose: it only has to tell the
 * decoder how to parse what follows. Forging it just makes decoding fail cleanly (MALFORMED);
 * unlike a signed field, it can't be tampered with to smuggle bad data past the signature,
 * because nothing downstream trusts it for anything beyond "which parser to use."
 *
 * <p>No separate version field lives inside {@link DiscoveryRecord} itself — the marker byte
 * is the sole version/format discriminator, matching how EncryptedFrame/RelayFrame/
 * DiscoveryFrame already do it elsewhere in this project, rather than duplicating that concern.
 */
public final class DiscoveryRecordCodec {

    private DiscoveryRecordCodec() {
    }

    private static final byte FORMAT_MARKER = 0x02;

    // Generous ceilings against a malicious or corrupt publisher forcing large allocations
    // while decoding untrusted bytes — same discipline as DiscoveryFrameCodec/PreKeyBundleCodec's
    // own bounds-checked length-prefix reads (see pre-m6-checklist.md's decoder-hardening pass).
    private static final int MAX_ADDRESSES = 16;
    private static final int MAX_ADDRESS_LENGTH = 512;
    private static final int MAX_BUNDLE_LENGTH = 16 * 1024;

    /**
     * Builds and signs a record. {@code rawPublicKey}/{@code rawPrivateKeySeed} are raw
     * 32-byte Ed25519 key material — see {@code IdentityService.rawPrivateKeySeed()} and
     * {@link Ed25519RecordKeys#rawPublicKeyFromX509} for how to get them from an existing
     * core-identity {@code Identity}. Deliberately takes raw bytes rather than an
     * {@code IdentityService}/{@code Identity} type: core-discovery has no dependency on
     * core-identity, matching the same "accept raw key material as parameters" convention
     * {@code PeerNetworkService.start()} and {@code PreKeyBundleFactory} already use.
     */
    public static byte[] encodeSigned(DiscoveryRecord record, byte[] rawPublicKey, byte[] rawPrivateKeySeed) {
        try {
            byte[] canonical = encodeCanonicalUnsigned(record);

            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(Ed25519RecordKeys.toPrivateKey(rawPrivateKeySeed));
            signer.update(canonical);
            byte[] signature = signer.sign();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeByte(FORMAT_MARKER);
            out.write(canonical);
            writeBytes(out, rawPublicKey);
            writeBytes(out, signature);
            return buffer.toByteArray();
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("Failed to encode signed discovery record", e);
        }
    }

    /**
     * The safe default entry point for a lookup caller: decodes, then verifies signature,
     * peer-ID binding, and expiry together, throwing with a specific
     * {@link DiscoveryRecordException.Reason} on the first check that fails rather than
     * returning a record the caller might forget to verify.
     *
     * <p>Checked in this order deliberately: a malformed payload can't be signature-checked;
     * an unverified signature can't be trusted to say whose key this even claims to be; and
     * peer-ID binding is the actual MITM defense, so it's checked before the merely-hygienic
     * expiry check.
     */
    public static DiscoveryRecord verifyAndDecode(byte[] wire, PeerId expectedPeerId, long nowMillis)
            throws DiscoveryRecordException {
        SignedDiscoveryRecord signed = decodeUnverified(wire);

        if (!signed.verifySignature()) {
            throw new DiscoveryRecordException(DiscoveryRecordException.Reason.BAD_SIGNATURE,
                    "Discovery record signature does not verify against its embedded public key");
        }
        String derivedPeerId = signed.derivedPeerId();
        if (!derivedPeerId.equals(expectedPeerId.value())) {
            throw new DiscoveryRecordException(DiscoveryRecordException.Reason.PEER_ID_MISMATCH,
                    "Discovery record's embedded key derives peer ID " + derivedPeerId
                            + ", expected " + expectedPeerId.value());
        }
        if (signed.record().isExpired(nowMillis)) {
            throw new DiscoveryRecordException(DiscoveryRecordException.Reason.EXPIRED,
                    "Discovery record expired at " + signed.record().expiresAt() + ", now " + nowMillis);
        }
        return signed.record();
    }

    /**
     * Decodes without verifying anything: the signature, the peer-ID binding, and the expiry
     * are all left unchecked. Real lookup callers should use {@link #verifyAndDecode} instead;
     * this exists for (a) tests that need to inspect or tamper with a decoded structure before
     * verifying it, and (b) {@code DiscoveryRegistry}'s own narrow expiry peek, which
     * deliberately does not verify signatures — the relay is not this system's trust boundary,
     * the looking-up peer is, so a server-side signature check would be security theater, not a
     * real guarantee (see {@code DiscoveryRegistry}'s own javadoc for why it peeks at all).
     */
    public static SignedDiscoveryRecord decodeUnverified(byte[] wire) throws DiscoveryRecordException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire));
            byte marker = in.readByte();
            if (marker != FORMAT_MARKER) {
                throw new DiscoveryRecordException(DiscoveryRecordException.Reason.MALFORMED,
                        "Unrecognized discovery record format marker: " + marker);
            }

            int addressCount = in.readInt();
            if (addressCount < 0 || addressCount > MAX_ADDRESSES) {
                throw new DiscoveryRecordException(DiscoveryRecordException.Reason.MALFORMED,
                        "Implausible address count: " + addressCount);
            }
            List<String> addresses = new ArrayList<>(addressCount);
            for (int i = 0; i < addressCount; i++) {
                addresses.add(readString(in, MAX_ADDRESS_LENGTH));
            }

            byte[] preKeyBundle = null;
            if (in.readByte() != 0) {
                preKeyBundle = readBytes(in, MAX_BUNDLE_LENGTH);
            }

            String relayMultiaddr = null;
            if (in.readByte() != 0) {
                relayMultiaddr = readString(in, MAX_ADDRESS_LENGTH);
            }

            long expiresAt = in.readLong();

            byte[] publicKey = readBytes(in, Ed25519RecordKeys.RAW_PUBLIC_KEY_LENGTH);
            if (publicKey.length != Ed25519RecordKeys.RAW_PUBLIC_KEY_LENGTH) {
                throw new DiscoveryRecordException(DiscoveryRecordException.Reason.MALFORMED,
                        "Public key must be exactly " + Ed25519RecordKeys.RAW_PUBLIC_KEY_LENGTH + " bytes");
            }
            byte[] signature = readBytes(in, Ed25519RecordKeys.SIGNATURE_LENGTH);
            if (signature.length != Ed25519RecordKeys.SIGNATURE_LENGTH) {
                throw new DiscoveryRecordException(DiscoveryRecordException.Reason.MALFORMED,
                        "Signature must be exactly " + Ed25519RecordKeys.SIGNATURE_LENGTH + " bytes");
            }

            DiscoveryRecord record = new DiscoveryRecord(addresses, preKeyBundle, relayMultiaddr, expiresAt);
            return new SignedDiscoveryRecord(record, publicKey, signature);
        } catch (DiscoveryRecordException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DiscoveryRecordException(DiscoveryRecordException.Reason.MALFORMED,
                    "Failed to parse discovery record: " + e.getMessage(), e);
        }
    }

    /**
     * The exact bytes that get signed: everything in the record except the marker, public key,
     * and signature itself. Package-private — {@link SignedDiscoveryRecord#verifySignature()}
     * is the only other caller, re-deriving the same canonical bytes to check the signature
     * against.
     */
    static byte[] encodeCanonicalUnsigned(DiscoveryRecord record) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);

        out.writeInt(record.addresses().size());
        for (String address : record.addresses()) {
            writeString(out, address);
        }

        if (record.hasPreKeyBundle()) {
            out.writeByte(1);
            writeBytes(out, record.preKeyBundle());
        } else {
            out.writeByte(0);
        }

        if (record.hasRelayMultiaddr()) {
            out.writeByte(1);
            writeString(out, record.relayMultiaddr());
        } else {
            out.writeByte(0);
        }

        out.writeLong(record.expiresAt());
        return buffer.toByteArray();
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readBytes(DataInputStream in, int maxLength)
            throws IOException, DiscoveryRecordException {
        int length = in.readInt();
        int available = in.available();
        if (length < 0 || length > maxLength || length > available) {
            throw new DiscoveryRecordException(DiscoveryRecordException.Reason.MALFORMED,
                    "Malformed length-prefixed field: length=" + length
                            + ", available=" + available + ", max=" + maxLength);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static String readString(DataInputStream in, int maxLength)
            throws IOException, DiscoveryRecordException {
        return new String(readBytes(in, maxLength), StandardCharsets.UTF_8);
    }
}
