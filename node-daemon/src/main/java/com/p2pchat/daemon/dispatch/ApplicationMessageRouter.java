package com.p2pchat.daemon.dispatch;

import com.p2pchat.filetransfer.wire.FileTransferMessageCodec;
import com.p2pchat.messaging.wire.ChatMessageCodec;
import com.p2pchat.messaging.wire.HandshakeMessageCodec;

/**
 * M6a: routes one decrypted application-layer payload — the plaintext {@code
 * SecureSessionService.decrypt(...)} hands back — to whichever of {@code ChatMessageCodec} or
 * {@code FileTransferMessageCodec} can actually decode it.
 *
 * <p><b>Why this doesn't need a new outer envelope.</b> {@code ChatMessageCodec} reserves marker
 * bytes {@code 2, 3, 4} (CHAT_MESSAGE, DELIVERY_RECEIPT, READ_RECEIPT); {@code
 * FileTransferMessageCodec} reserves {@code 6, 7, 8} (FILE_OFFER, FILE_CHUNK_REQUEST,
 * FILE_CHUNK). Both draw from the same numbering docs/architecture-spec.md §6's {@code
 * EnvelopeType} enum already assigned, and the two ranges are already disjoint — neither codec
 * had to change for this router to exist. Routing is a single-byte peek, not a new wire format.
 *
 * <p><b>Markers 0, 1, 5, 9 are reserved, not unknown</b> — see the switch below and
 * docs/adr/0001 for why 5 (GROUP_OP) and 9 (PRESENCE_PING) are still unimplemented (resolving to
 * {@link DispatchedMessage.Unknown}, not an error) and why "unimplemented" is not the same thing
 * as malformed. In particular,
 * marker 0/1 (HANDSHAKE_INIT/HANDSHAKE_RESPONSE) are unlikely to ever need a codec at this layer
 * at all: PQXDH session establishment already happens transparently inside {@code
 * SecureSessionService.decrypt(...)}, via libsignal's own PreKeySignalMessage/SignalMessage
 * distinction (the {@code EncryptedFrame} 0x01/0x02 marker, one layer below this one) — by the
 * time a marker byte reaches this router, a session already exists. Seeing 0 or 1 here would
 * mean either a corrupt/malicious payload, or a future milestone adding a genuine
 * application-layer handshake concept this project doesn't have yet.
 *
 * <p>Lives in {@code node-daemon}, not a shared {@code core-*} module: there is exactly one
 * consumer of this concept right now (the M6 daemon). Promoting it to a shared module before a
 * second real consumer exists (e.g. a future Android client reusing core-* modules directly)
 * would be guessing at a shape it hasn't earned yet — the same reasoning {@code core-model} and
 * {@code DialableAddressResolver} were already promoted/kept local under.
 */
public final class ApplicationMessageRouter {

    // Not a redefinition of a shared EnvelopeType — no such Java enum exists yet (the .proto
    // sketch in docs/architecture-spec.md §6 is still the only source of truth; api-contract
    // isn't scaffolded). These are just the marker values this router needs to recognize,
    // named the same way §6 names them, kept private the same way every other codec in this
    // project keeps its own marker constants private.
    private static final byte HANDSHAKE_INIT = 0;
    private static final byte HANDSHAKE_RESPONSE = 1;
    private static final byte CHAT_MESSAGE = 2;
    private static final byte DELIVERY_RECEIPT = 3;
    private static final byte READ_RECEIPT = 4;
    private static final byte GROUP_OP = 5;
    private static final byte FILE_OFFER = 6;
    private static final byte FILE_CHUNK_REQUEST = 7;
    private static final byte FILE_CHUNK = 8;
    private static final byte PRESENCE_PING = 9;

    private ApplicationMessageRouter() {
    }

    /**
     * @param plaintext the decrypted payload — {@code SecureSessionService.decrypt(...)}'s
     *                   return value, unmodified. The marker byte is peeked, not consumed; the
     *                   full array (marker included) is forwarded to whichever codec handles it,
     *                   since both codecs' own {@code decode()} methods expect the marker still
     *                   present at index 0.
     * @throws IllegalArgumentException if {@code plaintext} is null, empty, or fails a
     *         recognized marker's own codec validation (malformed length-prefixed fields,
     *         truncated payload, etc.). A reserved-but-unimplemented or genuinely unrecognized
     *         marker no longer throws — see {@link DispatchedMessage.Unknown} and docs/adr/0001
     *         category 1.
     */
    public static DispatchedMessage dispatch(byte[] plaintext) {
        if (plaintext == null || plaintext.length < 1) {
            throw new IllegalArgumentException(
                    "Decrypted payload is empty (or null) \u2014 cannot determine application message type");
        }
        byte marker = plaintext[0];
        return switch (marker) {
            case CHAT_MESSAGE, DELIVERY_RECEIPT, READ_RECEIPT ->
                    new DispatchedMessage.Chat(ChatMessageCodec.decode(plaintext));
            case FILE_OFFER, FILE_CHUNK_REQUEST, FILE_CHUNK ->
                    new DispatchedMessage.FileTransfer(FileTransferMessageCodec.decode(plaintext));
            case HANDSHAKE_INIT, HANDSHAKE_RESPONSE ->
                    new DispatchedMessage.Handshake(HandshakeMessageCodec.decode(plaintext));
            // docs/adr/0001 category 1 ("ignore and log"): GROUP_OP/PRESENCE_PING are reserved
            // for M8/presence but not implemented yet, and any other value is a marker this
            // build has simply never heard of -- neither is malformed data, both are the
            // expected, permanent steady state of a mixed-version network the ADR describes.
            // A genuinely corrupt payload still throws, from the codecs' own decode() calls
            // above -- a marker says nothing about whether the bytes after it are well-formed,
            // so there's no format to validate an unrecognized marker's payload against here.
            case GROUP_OP, PRESENCE_PING -> new DispatchedMessage.Unknown(Byte.toUnsignedInt(marker));
            default -> new DispatchedMessage.Unknown(Byte.toUnsignedInt(marker));
        };
    }
}
