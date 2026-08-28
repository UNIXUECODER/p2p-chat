package com.p2pchat.daemon.contact;

import com.p2pchat.model.PeerId;

import java.util.Objects;

/**
 * The decoded shape of a p2p-chat invite code — {@code docs/M6g-gap-analysis-and-plan.md §2.1}.
 * Deliberately does not carry a pre-key bundle: that's what signed discovery records (M6f) are
 * for, and embedding one here would be a second, unsecured distribution channel for cryptographic
 * material, defeating the entire point of M6f's signature verification — see §2.1's own
 * rationale, which this just implements rather than restates.
 *
 * @param peerId           the invitee's libp2p peer ID — the one required field.
 * @param discoveryAddress the discovery/relay server multiaddr to look {@code peerId} up
 *                          against, or {@code null} to fall back to this daemon's own configured
 *                          default (see {@code ContactService}).
 * @param displayName      cosmetic only, per §2.1 — "not trusted for identity." Shown to the
 *                          user before discovery completes; never used to verify who this
 *                          actually is.
 */
public record InviteCode(PeerId peerId, String discoveryAddress, String displayName) {
    public InviteCode {
        Objects.requireNonNull(peerId, "peerId must not be null");
    }
}
