package com.p2pchat.daemon.contact;

import com.p2pchat.daemon.json.JsonCodec;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonValue;
import com.p2pchat.model.PeerId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes/decodes the invite code format {@code docs/M6g-gap-analysis-and-plan.md §2.1} defines:
 * a base64url (no {@code +}, {@code /}, or {@code =} padding — safe for QR codes, URLs, and
 * copy-paste) encoding of a small JSON object {@code {"p": ..., "d": ..., "n": ...}}, built on
 * M6c's {@link JsonCodec} rather than a seventh hand-rolled binary wire format. Every other codec
 * in this project (RelayFrameCodec, DiscoveryFrameCodec, ChatMessageCodec, PreKeyBundleCodec...)
 * is length-prefixed binary; this one genuinely is different, on purpose: an invite code is
 * base64-opaque either way (nothing about JSON makes it more human-readable once encoded), so the
 * usual "binary is smaller and this project already has the tooling" reasoning doesn't actually
 * favor binary here, and reusing M6c's already-built, already-tested JSON layer beats inventing
 * an eighth format for a payload this small and this infrequent.
 *
 * <p>Field order on encode is always {@code p}, then {@code d} (if present), then {@code n} (if
 * present) — {@link JsonObject}'s insertion-order-preserving guarantee makes this deterministic,
 * so {@code encode(decode(x))} reproduces {@code x} byte-for-byte given the same set of present
 * fields, not just something semantically equivalent to it.
 */
public final class InviteCodeCodec {

    private InviteCodeCodec() {
    }

    public static String encode(InviteCode code) {
        JsonObject.Builder builder = JsonObject.builder().put("p", code.peerId().value());
        if (code.discoveryAddress() != null) {
            builder.put("d", code.discoveryAddress());
        }
        if (code.displayName() != null) {
            builder.put("n", code.displayName());
        }
        String json = JsonCodec.write(builder.build());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static InviteCode decode(String inviteCode) throws InviteCodeException {
        byte[] jsonBytes;
        try {
            jsonBytes = Base64.getUrlDecoder().decode(inviteCode);
        } catch (IllegalArgumentException e) {
            throw new InviteCodeException("Invite code is not valid base64url: " + e.getMessage(), e);
        }
        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        JsonValue parsed;
        try {
            parsed = JsonCodec.parse(json);
        } catch (IllegalArgumentException e) {
            throw new InviteCodeException("Invite code did not decode to valid JSON: " + e.getMessage(), e);
        }

        JsonObject object;
        try {
            object = parsed.asObject();
        } catch (IllegalStateException e) {
            throw new InviteCodeException("Invite code JSON must be an object: " + e.getMessage(), e);
        }

        if (!object.has("p") || object.get("p").isNull()) {
            throw new InviteCodeException("Invite code is missing required field 'p' (peer ID)");
        }
        String peerIdValue;
        try {
            peerIdValue = object.get("p").asString();
        } catch (IllegalStateException e) {
            throw new InviteCodeException("Invite code field 'p' must be a string: " + e.getMessage(), e);
        }
        PeerId peerId;
        try {
            peerId = PeerId.of(peerIdValue);
        } catch (IllegalArgumentException e) {
            throw new InviteCodeException("Invite code field 'p' is not a usable peer ID: " + e.getMessage(), e);
        }

        String discoveryAddress = readOptionalString(object, "d");
        String displayName = readOptionalString(object, "n");
        return new InviteCode(peerId, discoveryAddress, displayName);
    }

    private static String readOptionalString(JsonObject object, String key) throws InviteCodeException {
        if (!object.has(key) || object.get(key).isNull()) {
            return null;
        }
        try {
            return object.get(key).asString();
        } catch (IllegalStateException e) {
            throw new InviteCodeException("Invite code field '" + key + "' must be a string: " + e.getMessage(), e);
        }
    }
}
