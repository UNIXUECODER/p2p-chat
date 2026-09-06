# ADR 0001: Wire protocol compatibility policy

> pre-m6h-hardening-plan.md finding B-3. First entry in `docs/adr/` — no ADR convention existed
> in this project before this; establishing `docs/adr/000N-title.md` as the pattern for future
> ones, numbered sequentially, one decision per file.

## Status

Accepted. Partially implemented — see "Current implementation status" below for the honest gap
check between what this document decides and what the code actually does today.

## Context

B-1 gave every hand-rolled codec a version byte. That closes "can a decoder tell corrupt from
too-new" but doesn't answer the actual policy question underneath it: when a node receives
something it doesn't fully understand — a message type it's never heard of, a field it doesn't
recognize, a protocol version newer than its own — what should it *do*? Without a written answer,
that question gets re-litigated separately for each of the four codecs, and for the router that
dispatches between them, with no guarantee the answers agree with each other. `GROUP_OP` and
`PRESENCE_PING` (reserved marker values 5 and 9, no codec yet) are the concrete near-term case:
M8 or a presence feature will eventually make these real, and a node running *before* that
milestone will keep receiving them from any peer running *after* it, for as long as this network
has nodes on different versions running at once — which, for a P2P system with no forced update
mechanism, is indefinitely.

## Decision

Three categories, three different responses, matching the audit's own framing exactly:

1. **Unknown message type** — a marker byte the receiving code has never heard of at all, or one
   it recognizes as reserved-for-later (`GROUP_OP`, `PRESENCE_PING`) but doesn't implement yet.
   **Response: ignore and log.** This is not an error. A node running behind a peer on protocol
   features is an expected, permanent steady state for this kind of network, not an exceptional
   one — treating it as an exception (throwing, tearing down state, surfacing it as a failure)
   punishes exactly the interoperability this policy exists to protect.

2. **Unknown field within an otherwise-recognized message** — a message type this build does
   understand, carrying additional data this build's version of that message's format doesn't
   define. **Response: ignore.** Decode what's recognized, discard what isn't, don't fail the
   whole message over it.

3. **A protocol version newer than this build supports** — the version byte itself (B-1) doesn't
   match. **Response: refuse, distinguishably.** Not silently drop, not attempt a best-effort
   partial decode of a format that might have changed shape entirely — refuse clearly, with an
   error a caller can tell apart from "this data is corrupt" (`UnsupportedProtocolVersionException`,
   B-1). "Refuse with a clear user-facing error" is the audit's own phrasing; the user-facing part
   is M7's job once a UI exists to phrase it in — what this project can do today is make sure the
   *daemon-level* error is already distinguishable, so M7 has something meaningful to translate
   rather than a generic exception to guess at.

Category 3 is deliberately the odd one out: categories 1 and 2 are about *tolerating* things this
build doesn't recognize, because tolerating them is what makes a mixed-version network function at
all. Category 3 is different in kind, not degree — a version bump means the *shape* of the format
itself may have changed, not just its content, so there's no safe partial interpretation to fall
back to the way there is for an unrecognized marker or an extra field. Refusing clearly is the
honest response, not a stricter version of the same tolerance.

## Current implementation status (honest gap check, not a separate follow-up document)

- **Category 3 — implemented.** All four B-1 codecs throw `UnsupportedProtocolVersionException`
  (itself an `IllegalArgumentException`, so existing broad catch sites keep working — see that
  exception's own Javadoc) for a version mismatch. Matches this decision as written.

- **Category 1 — NOT yet implemented; this is a real, tracked gap, not a rounding error.**
  `ApplicationMessageRouter.dispatch()` currently *throws* `IllegalArgumentException` for
  `GROUP_OP`, `PRESENCE_PING`, and any genuinely unrecognized marker — the opposite of "ignore and
  log." Writing this ADR is what surfaced the gap: the router's own reserved-marker handling
  predates B-2 and was written when "a marker this build doesn't implement" and "a marker that's
  simply wrong" were still the same case in practice. They aren't anymore, now that `GROUP_OP` and
  `PRESENCE_PING` are concretely on a roadmap (M8, presence) rather than hypothetically reserved.
  **Deliberately not fixed in this same commit.** Changing `dispatch()`'s return contract for this
  case (does it return `null`? A new `DispatchedMessage.Unknown` variant callers must handle in
  every existing exhaustive `switch`, including `SessionManager`'s?) is a real design decision with
  its own regression surface across every caller of `dispatch()`, not a one-line fix — exactly the
  kind of change this hardening pass has been deliberately scoping tightly and testing
  individually, not bundling into a documentation commit. Tracked as a follow-up, next in line
  after this ADR, before M6h.

- **Category 2 — not applicable yet, not a gap.** None of the four B-1 codecs have an extensible
  "unknown trailing field" concept — they're fixed positional binary formats, not a TLV or
  protobuf-style structure with room for forward-compatible additions. This category is written
  down now as a commitment for *if and when* such a mechanism is added (a natural companion to any
  future work on extensible message formats), not because it applies to today's wire formats.

## Consequences

- The next time a marker value needs handling in `ApplicationMessageRouter`, the answer is already
  decided: recognized-but-unimplemented is a log line, not an exception. The follow-up item above
  is that decision waiting to be executed, not re-derived.
- `UnsupportedProtocolVersionException`'s existence (B-1) is retroactively justified by something
  more than "the audit asked for it" — it's the concrete mechanism Category 3 of this policy
  depends on already existing before this ADR could even describe using it.
- A future MLS/group-chat message format, or any other genuinely new wire shape, inherits this
  policy without needing its own version of this conversation.
