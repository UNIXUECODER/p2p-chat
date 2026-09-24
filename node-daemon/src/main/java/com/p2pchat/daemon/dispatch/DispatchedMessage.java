package com.p2pchat.daemon.dispatch;

import com.p2pchat.filetransfer.wire.FileTransferMessage;
import com.p2pchat.messaging.wire.ChatWireMessage;
import com.p2pchat.messaging.wire.HandshakeWireMessage;

/**
 * The result of routing one decrypted application-layer payload via {@link ApplicationMessageRouter}.
 *
 * <p><b>Why this wrapper exists instead of a shared hierarchy.</b> {@code ChatWireMessage}
 * (M5b) and {@code FileTransferMessage} (M4b) are deliberately two independent sealed
 * interfaces — both Javadocs say so explicitly, and both named the same condition for changing
 * that: a caller that needs to field either kind from one decrypted byte stream. That caller
 * didn't exist through M4 (file-transfer-only) or M5c (chat-only). It exists now — M6's daemon
 * holds concurrent sessions where either kind can arrive on the same {@code SecureSessionService
 * .decrypt(...)} call.
 *
 * <p>Given that, there were two ways to give the caller one thing to match on: retrofit
 * {@code ChatWireMessage}/{@code FileTransferMessage} to share a common supertype, or wrap them
 * from the outside. Retrofitting was rejected — both hierarchies are already shipped (M4b, M5b),
 * proven, and referenced by their own codecs' {@code decode()}/{@code encode()} signatures;
 * changing either to `implement` some new shared interface is a real, if small, change to two
 * already-stable modules for a need that belongs to a third (node-daemon). A caller-side wrapper
 * gets the same exhaustive-pattern-matching benefit without core-messaging or core-filetransfer
 * knowing this type exists, or changing a single line.
 *
 * <p>Deliberately not a {@code record} holding {@code Object payload} with an instanceof check
 * at the call site — that would give up the exhaustiveness checking a sealed interface + switch
 * pattern match provides. Two thin records, one per existing hierarchy, is the minimum needed to
 * keep that.
 */
public sealed interface DispatchedMessage {

    /**
     * @param message one of {@code ChatMessagePayload}, {@code DeliveryReceiptPayload}, or
     *                {@code ReadReceiptPayload} — see {@code ChatWireMessage}'s own permits clause.
     */
    record Chat(ChatWireMessage message) implements DispatchedMessage {
    }

    /**
     * @param message one of {@code FileOfferPayload}, {@code FileChunkRequestPayload}, or
     *                {@code FileChunkPayload} — see {@code FileTransferMessage}'s own permits clause.
     */
    record FileTransfer(FileTransferMessage message) implements DispatchedMessage {
    }

    /**
     * pre-m6h-hardening-plan.md finding B-2. {@code HANDSHAKE_INIT}/{@code HANDSHAKE_RESPONSE}
     * markers 0/1 used to reach this router only as a "should never happen" case (PQXDH session
     * establishment happens one layer below, inside {@code SecureSessionService.decrypt(...)}) —
     * this is the genuine application-layer handshake concept that assumption anticipated, now
     * that it exists.
     *
     * @param message one of {@code HandshakeInitPayload} or {@code HandshakeResponsePayload} —
     *                see {@code HandshakeWireMessage}'s own permits clause.
     */
    record Handshake(HandshakeWireMessage message) implements DispatchedMessage {
    }

    /**
     * docs/adr/0001 category 1 ("ignore and log"): a marker byte this build recognizes as
     * reserved-for-later ({@code GROUP_OP}, {@code PRESENCE_PING}) or simply doesn't recognize at
     * all. Deliberately not an exception — see the ADR's own reasoning for why a mixed-version
     * network must tolerate this as a permanent, expected steady state rather than an error.
     *
     * <p>Carries the raw marker so a caller can log something more useful than "an unknown
     * message arrived." {@code int}, not {@code byte} — {@link ApplicationMessageRouter} widens
     * it unsigned before constructing this, so a marker of 200 logs as {@code 200}, not the
     * {@code -56} Java's signed {@code byte} would otherwise print.
     *
     * @param marker the unrecognized (or reserved-but-unimplemented) marker byte, unsigned-widened
     */
    record Unknown(int marker) implements DispatchedMessage {
    }
}
