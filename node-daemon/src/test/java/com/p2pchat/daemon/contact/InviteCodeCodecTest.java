package com.p2pchat.daemon.contact;

import com.p2pchat.model.PeerId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InviteCodeCodecTest {

    @Test
    void roundTripsAFullyPopulatedInviteCode() throws Exception {
        InviteCode original = new InviteCode(PeerId.of("12D3KooWAlice"), "/ip4/1.2.3.4/tcp/9000/p2p/12D3KooWRelay", "Alice");
        String encoded = InviteCodeCodec.encode(original);

        assertThat(encoded).doesNotContain("+", "/", "="); // base64url, no padding — must be QR/URL/copy-paste safe

        InviteCode decoded = InviteCodeCodec.decode(encoded);
        assertThat(decoded.peerId()).isEqualTo(original.peerId());
        assertThat(decoded.discoveryAddress()).isEqualTo(original.discoveryAddress());
        assertThat(decoded.displayName()).isEqualTo(original.displayName());

        // Deterministic field order (p, then d, then n) means this isn't just semantically
        // equivalent -- it's byte-for-byte the same string.
        assertThat(InviteCodeCodec.encode(decoded)).isEqualTo(encoded);
    }

    @Test
    void roundTripsAMinimalInviteCodeWithOnlyThePeerId() throws Exception {
        InviteCode minimal = new InviteCode(PeerId.of("12D3KooWBob"), null, null);
        InviteCode decoded = InviteCodeCodec.decode(InviteCodeCodec.encode(minimal));

        assertThat(decoded.peerId()).isEqualTo(minimal.peerId());
        assertThat(decoded.discoveryAddress()).isNull();
        assertThat(decoded.displayName()).isNull();
    }

    @Test
    void rejectsInputThatIsNotValidBase64Url() {
        assertThatThrownBy(() -> InviteCodeCodec.decode("this has spaces and !!! which aren't base64url"))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("base64url");
    }

    @Test
    void rejectsValidBase64ThatDecodesToNonJson() {
        String encoded = base64Of("this is not json at all { [ }");
        assertThatThrownBy(() -> InviteCodeCodec.decode(encoded))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsValidJsonThatIsNotAnObject() {
        String encoded = base64Of("[1,2,3]");
        assertThatThrownBy(() -> InviteCodeCodec.decode(encoded))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("object");
    }

    @Test
    void rejectsAnInviteCodeMissingTheRequiredPeerIdField() {
        String encoded = base64Of("{\"d\":\"/ip4/1.2.3.4/tcp/9000\",\"n\":\"Alice\"}");
        assertThatThrownBy(() -> InviteCodeCodec.decode(encoded))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("'p'");
    }

    @Test
    void rejectsAnInviteCodeWherePeerIdFieldIsNotAString() {
        String encoded = base64Of("{\"p\":12345}");
        assertThatThrownBy(() -> InviteCodeCodec.decode(encoded))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("'p'");
    }

    private static String base64Of(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
