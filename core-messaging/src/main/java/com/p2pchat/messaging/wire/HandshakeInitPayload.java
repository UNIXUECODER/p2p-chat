package com.p2pchat.messaging.wire;

import java.util.Objects;
import java.util.Set;

/**
 * Sent once, automatically, right after a session is freshly established (see {@code
 * SessionManager}'s own wiring) — the initiating side's declaration of which optional protocol
 * features it supports. The receiving side replies with a {@link HandshakeResponsePayload} of its
 * own; neither side blocks waiting for the other's before proceeding with real traffic, since a
 * capability is only consulted when something that actually needs it is about to happen (e.g. a
 * future M8 offering to start a group conversation), not before every message.
 *
 * <p>Deliberately an open-ended {@code Set<String>}, not a closed enum enumerating every
 * capability this build happens to know about today. A closed enum would mean every future
 * capability addition (M8's {@code "groups"}, an eventual {@code "mls"}) requires a wire-format
 * change here; a peer that has never heard of a given string simply doesn't recognize it as
 * present in the set — this is the whole reason B-2 exists per the audit ("what makes M8
 * additive rather than breaking, and it is far cheaper to add now than to retrofit").
 *
 * @param senderAddress          this node's own dialable multiaddr — same convention as {@code
 *                               ChatMessagePayload.senderAddress}, and for the same reason: this
 *                               is how the receiving side knows where to send its {@link
 *                               HandshakeResponsePayload} back to (see {@code SessionManager}'s
 *                               own reply wiring). Never null or empty.
 * @param supportedCapabilities feature-name strings this build actually supports — see {@code
 *                               SessionManager.SUPPORTED_CAPABILITIES} for the current real list.
 *                               Never null; individual entries never null or blank.
 */
public record HandshakeInitPayload(String senderAddress, Set<String> supportedCapabilities)
        implements HandshakeWireMessage {

    public HandshakeInitPayload {
        if (senderAddress == null || senderAddress.isEmpty()) {
            throw new IllegalArgumentException("senderAddress must not be null or empty");
        }
        Objects.requireNonNull(supportedCapabilities, "supportedCapabilities");
        for (String capability : supportedCapabilities) {
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("supportedCapabilities must not contain null/blank entries");
            }
        }
        supportedCapabilities = Set.copyOf(supportedCapabilities); // defensive + immutable, same reasoning as any other collection-typed record component
    }
}
