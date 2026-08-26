package com.p2pchat.discovery;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.interfaces.EdECPublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These are not hand-traced claims: the peer-ID derivation below is checked against the
 * <i>official</i> libp2p peer-id spec's own published Ed25519 test vector
 * (github.com/libp2p/specs, {@code peer-ids/peer-ids.md}), and independently cross-checked
 * against a second implementation written in Python using a different base58 library before
 * any of this code existed. See the M6f section of README.md for the full verification story.
 */
class Ed25519RecordKeysTest {

    // From the official libp2p peer-ids spec: protobuf(PublicKey{Type=Ed25519, Data=<32 bytes>}).
    private static final String SPEC_PUBKEY_PROTOBUF_HEX =
            "080112201ed1e8fae2c4a144b8be8fd4b47bf3d3b34b871c3cacf6010f0e42d474fce27e";
    // Independently cross-checked (Python, python-base58) against the raw key above.
    private static final String SPEC_EXPECTED_PEER_ID =
            "12D3KooWBtg3aaRMjxwedh83aGiUkwSxDwUZkzuJcfaqUmo7R3pq";

    @Test
    void derivesPeerIdMatchingOfficialLibp2pSpecVector() {
        byte[] specProtobuf = hex(SPEC_PUBKEY_PROTOBUF_HEX);
        // header: 0x08 0x01 (Type=Ed25519), 0x12 0x20 (Data, 32 bytes)
        byte[] raw32 = Arrays.copyOfRange(specProtobuf, 4, 36);

        String peerId = Ed25519RecordKeys.peerIdFromRawPublicKey(raw32);

        assertThat(peerId).isEqualTo(SPEC_EXPECTED_PEER_ID);
    }

    @Test
    void realJdkKeypairRoundTripsThroughX509ExtractionSigningAndPeerIdDerivation() throws Exception {
        for (int trial = 0; trial < 5; trial++) {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] x509 = kp.getPublic().getEncoded();

            byte[] rawPublicKey = Ed25519RecordKeys.rawPublicKeyFromX509(x509);
            assertThat(rawPublicKey).hasSize(32);

            // Cross-check the X.509 extraction against an independent extraction path (via
            // EdECPoint) rather than trusting the fixed-prefix assumption alone.
            assertThat(rawPublicKey).isEqualTo(rawFromEdECPoint(kp.getPublic()));

            String peerId = Ed25519RecordKeys.peerIdFromRawPublicKey(rawPublicKey);
            assertThat(peerId).startsWith("12D3KooW");

            // Reconstructing PublicKey/PrivateKey purely from raw bytes (no original KeyPair
            // object) must still produce working sign/verify — this is exactly what happens on
            // both ends of a real discovery record.
            PrivateKey reconstructedPrivate = Ed25519RecordKeys.toPrivateKey(rawSeedOf(kp.getPrivate()));
            byte[] message = ("trial " + trial).getBytes();
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(reconstructedPrivate);
            signer.update(message);
            byte[] signature = signer.sign();

            PublicKey reconstructedPublic = Ed25519RecordKeys.toPublicKey(rawPublicKey);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(reconstructedPublic);
            verifier.update(message);
            assertThat(verifier.verify(signature)).isTrue();
        }
    }

    @Test
    void rejectsWrongLengthPublicKey() {
        assertThatThrownBy(() -> Ed25519RecordKeys.toPublicKey(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed25519RecordKeys.peerIdFromRawPublicKey(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongLengthPrivateKeySeed() {
        assertThatThrownBy(() -> Ed25519RecordKeys.toPrivateKey(new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsX509BytesThatArentEd25519() {
        assertThatThrownBy(() -> Ed25519RecordKeys.rawPublicKeyFromX509(new byte[44]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentKeysDeriveDifferentPeerIds() throws Exception {
        KeyPair a = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair b = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        String peerIdA = Ed25519RecordKeys.peerIdFromRawPublicKey(
                Ed25519RecordKeys.rawPublicKeyFromX509(a.getPublic().getEncoded()));
        String peerIdB = Ed25519RecordKeys.peerIdFromRawPublicKey(
                Ed25519RecordKeys.rawPublicKeyFromX509(b.getPublic().getEncoded()));

        assertThat(peerIdA).isNotEqualTo(peerIdB);
    }

    // --- test-only helpers, independent of Ed25519RecordKeys' own extraction logic ---

    private static byte[] rawFromEdECPoint(PublicKey publicKey) {
        EdECPublicKey edPub = (EdECPublicKey) publicKey;
        EdECPoint point = edPub.getPoint();
        byte[] y = point.getY().toByteArray(); // big-endian, two's complement
        byte[] raw = new byte[32];
        int n = Math.min(y.length, 32);
        for (int i = 0; i < n; i++) {
            raw[i] = y[y.length - 1 - i];
        }
        if (point.isXOdd()) {
            raw[31] |= (byte) 0x80;
        }
        return raw;
    }

    private static byte[] rawSeedOf(PrivateKey privateKey) {
        // PKCS8-encoded Ed25519 private keys have a fixed 16-byte prefix followed by the raw
        // 32-byte seed, mirroring the fixed 12-byte X.509 prefix confirmed for public keys.
        byte[] pkcs8 = privateKey.getEncoded();
        return Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
    }

    private static byte[] hex(String hexString) {
        int len = hexString.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return out;
    }
}
