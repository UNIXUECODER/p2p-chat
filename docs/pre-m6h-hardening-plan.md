# Pre-M6H Hardening & Remediation Plan

> **Status:** Proposed — not yet started
>
> **Update (M6g-5):** Phase 0 (hygiene) and Phase 1 (the four 🔴 findings — C-1 through C-4) below
> are done and tested (see README's own M6g-5 section and `docs/M6-roadmap-and-decisions.md` for
> the full writeup, and `docs/verification-vocabulary.md` for what "tested" means here precisely).
> This document's own findings and reasoning are left exactly as originally written below —
> this note records status, it doesn't edit the audit. Tracks A and B, and Phases 2 onward, are
> still open.
>
> **Update (M6g-6):** Track B (protocol versioning — B-1, B-2, B-3) below is also done — see
> README's M6g-6 section and `docs/adr/0001-wire-protocol-compatibility-policy.md`. Writing B-3's
> ADR surfaced a real gap between the policy it documents and `ApplicationMessageRouter`'s actual
> behavior for unrecognized markers (throws; the policy says ignore+log) — recorded as a tracked
> follow-up in the ADR itself, not fixed in the same pass, and not yet run on real hardware (see
> the same README section for exactly what is and isn't confirmed).
>
> **Update (Track A-1 & A-2):** Track A-1 (persistent relay session, keepalive, exponential backoff)
> and Track A-2 (relay inbound frame handling via `handleRelayDeliveredFrame`, ADR-0001 unknown
> marker compliance) are both complete, unit-tested (403/403 tests passing repository-wide), and
> verified on real hardware via 3-terminal test matrix (two NAT-simulated daemons exchanging messages
> and delivery receipts over relay). Track A-3 (relay store-and-forward spool) is next.
>
> **Update (second-pass audit, 2026-10-02):** A second audit pass against HEAD `194da04` found
> issues the first pass did not. Nothing above is edited; the new material is §10–§16 at the end
> of this document. In short: **A-4 is expanded** into seven parts (§11), because the relay
> *selection*, startup behaviour, the invite code's `d` field and publication of this node's own
> discovery record turned out to be larger than "a config file"; **thirteen new items, Track F**
> (§12), cover the shared-database-connection race (reproduced), ratchet thread-safety, transport
> framing, receipt authorization, identity binding, resource bounds, delivery reliability, the
> pre-key lifecycle, path selection, API/lifecycle defects, I/O-thread blocking, and smaller
> defects; a **format-break window** (§10.5) batches every wire change into one coordinated
> change; and a **decision register** (§13) lists the choices that need a human. Two stress tests
> (F-2, F-3) are being run by the project owner; their results go in §15. §9's statement that the
> concurrency is "correct and well-reasoned" is amended (§10.3). Track A-3 stands for its stated
> checkpoint, with follow-ups. Track A-4 remains open.
>
> **Update (2026-10-03):** The project owner's stress tests for F-2 and F-3 are in and recorded in
> §15. **All three suspected defects reproduced on the real stack**: concurrent `encrypt` on one
> session produced duplicate message counters (F-2); a 2 MiB write arrived as two 1 MiB
> `onMessage` calls on the pinned jvm-libp2p 1.3.4 (F-3a); and 20 sequential `sendEnvelope` calls
> left 20 open streams on *both* sender and receiver (F-3b). F-2 and F-3b lose their
> "provisional/unconfirmed" status; F-3b is upgraded to 🔴. §15 also records six caveats about the
> tests as run (one since resolved; notably that the F-2 tests assert nothing) and the conversion
> each needs to become a regression test under R-5. A follow-up run on the exact production store
> composition has since reproduced F-2 as well: 4 distinct counters across 20 frames.
>
> **Update (2026-10-03, decisions):** DR-1…DR-9 are **accepted with amendments** and DR-10 is
> added (§13). Review of the decisions surfaced three gaps, now folded in: **F-8b** retries could
> multiply spooled copies (fix: relay `STORED` ack, DR-10); **F-5** needs a quarantine rule because
> the binding arrives inside the encrypted session, possibly after the first chat message; **F-9**
> adds a signed record `sequence` so a relay cannot replay an older record. F-6's dial policy is
> tightened (attached-subnet rule, DNS handling) and shared with F-10b.
>
> **Update (2026-10-03, F-1):** **F-1 is implemented** and delivered as a patch, awaiting your
> Gradle run on the pinned versions (status under F-1). Writing its tests surfaced two further
> failure modes on the original code: overlapping transactions *throw* at runtime, and an `Error`
> inside a transaction *commits* partial work.
>
> **Author:** Audit pass conducted against commit `3a14054` (M6g-4)
> **Scope:** Everything that should be done *before* M6h (`DaemonMain`), and before M7 (UI).
>
> This document supersedes nothing. It sits alongside `M6g-gap-analysis-and-plan.md`, which
> remains the authority on M6g's own scope and on M6h's inherited backlog. Where this document
> and the Feature Tracking Matrix disagree on *priority*, this document explains why.

---

## 0. How to read this document

Work is grouped into five tracks. **Track A is blocking** — those items change the shape of
`DaemonMain`, so building M6h first means building it twice. Tracks B–E are ordered by
value-per-hour, and can be interleaved.

Each item states: **what**, **why it matters**, **where** (real file/line references), and
**done-when**. Anything marked 🔴 is a confirmed defect found by reading the code, not a
stylistic preference.

| Track | Theme | Blocking M6h? |
|:---|:---|:---|
| **A** | Relay becomes a real transport | **Yes** |
| **B** | Protocol versioning & forward compatibility | **Yes** |
| **C** | Security hardening (confirmed defects) | Partly |
| **D** | Verification infrastructure & repo hygiene | No, but cheap |
| **E** | Documentation restructure | No |

Then: **M6h**, the **NAT traversal programme**, and **M7**.

---

## 1. Audit findings — new issues found in this pass

These were **not** previously tracked in the README or the gap-analysis matrix. Each was
confirmed by reading the actual source, not inferred.

### 🔴 A-1. Relay inbound frames are silently discarded

`ConnectionStrategy.send()` constructs an anonymous `RelayEventHandler` whose `onFrame` body is
**empty**:

```java
RelayController relay = network.connectToRelay(relayMultiaddr, new RelayEventHandler() {
    @Override public void onConnected(PeerId peerId, RelayController controller) { }
    @Override public void onFrame(PeerId sender, RelayFrame frame) { }   // <-- dropped
});
```

Combined with `SessionManager.start()` registering only `OnEnvelopeMessage` (a known, tracked
gap), the relay path is **write-only**. Two peers who can only reach each other via relay can
send but never receive. This is more severe than the matrix's "Relay-delivered inbound reception
— Not wired" row implies: it is not merely unwired, there is an active discard site.

**Where:** `core-network/.../ConnectionStrategy.java` (~line 52); `node-daemon/.../SessionManager.java` `start()`.

### 🔴 A-2. Relay connection leak — a new dial per message

`ConnectionStrategy.send()` calls `network.connectToRelay(...)` on **every relayed send**.
`RelayController` exposes exactly one method — `void send(RelayFrame)` — with **no `close()`**,
and nothing anywhere in the repository closes a relay connection.

Every relayed message therefore opens a fresh relay connection that survives until process exit.
Under sustained relay use this exhausts file descriptors on both peer and relay.

**Where:** `core-network/.../ConnectionStrategy.java`; `core-network/.../RelayController.java`.

### 🔴 A-3. Relay drops messages for offline peers, silently

`RelayRegistry` is a `ConcurrentHashMap<String, RelayController>` populated by `onConnected`.
If the target is not connected at that instant the frame is logged and dropped:

```java
RelayController target = registered.get(frame.peerId());
if (target == null) { /* "no route ... dropped" */ return; }
```

This means relay delivery only works when **both peers are online simultaneously**. For a chat
application that is the difference between a messenger and an intercom. The matrix lists this as
*Post-M6*; it should be **M6h**, because store-and-forward is what makes the relay a viable
answer to NAT at all (see §2).

**Where:** `relay-server/.../RelayRegistry.java`.

### 🔴 C-1. WebSocket control API binds to all interfaces, with no authentication

`DaemonWebSocketServer.start(int port)` calls `bootstrap.bind(port)` — the **single-argument**
overload, which binds `0.0.0.0`, not loopback. There is no `Origin` header check, no auth token,
and no pairing step (`grep` for `Origin|token|auth` in `daemon/ws/` returns nothing).

The class Javadoc says `ws://127.0.0.1:<port>/v1`. The code does not implement that.

Consequences, in order of severity:
1. **Anyone on the same LAN** (coffee shop, office, hotel Wi-Fi) can connect to the daemon and
   call every RPC method: read all message history, list contacts, send messages as the user,
   and exfiltrate files via `files.send`.
2. **Any website** the user visits can connect. Browsers do not apply the same-origin policy to
   WebSockets — they send an `Origin` header and expect the *server* to check it. Nothing does.
   This is the classic "cross-site WebSocket hijacking" vector.

This is the most serious finding in the audit. It is a full authentication bypass of the entire
local API.

**Where:** `node-daemon/.../ws/DaemonWebSocketServer.java` `start()`.

### 🔴 C-2. Private keys written world-readable, unencrypted

`JavaIdentityService.createIdentity()` writes the Ed25519 private key with plain `Files.write`:

```java
Files.write(baseDir.resolve("identity.key"), privateKeyBytes);
```

`SignalIdentityVault` does the same for the Signal identity keypair. No
`PosixFilePermissions`, no `0600`. On a shared or multi-user machine the keys inherit the
default umask and are typically group/world-readable.

The README tracks "OS keychain integration — Pre-release" as the fix. That is the *full* fix;
`chmod 600` is a two-line partial fix that removes the most likely real-world exposure and
should not wait for keychain work.

**Where:** `core-identity/.../JavaIdentityService.java`; `core-crypto/.../SignalIdentityVault.java`.

### 🔴 C-3. Unvalidated `FileOfferPayload` fields enable remote resource exhaustion

`grep` for bounds on `totalChunks`, `chunkSize`, `fileSize` across `core-filetransfer` and
`node-daemon` returns **nothing**. `DefaultFileTransferHandler.onFileOffer` takes all three
straight from the wire and persists them.

Then in `onFileChunk`:

```java
raf.seek((long) chunk.chunkIndex() * transfer.chunkSize);
raf.write(plaintext);
```

`chunkIndex` is never checked against `totalChunks`. A peer with an established session can send
`chunkIndex = Integer.MAX_VALUE` with a large `chunkSize` and cause a seek far past EOF —
`RandomAccessFile` will happily extend the file, creating a **sparse file of arbitrary size** and
filling the victim's disk. A hostile `totalChunks` also drives `missingChunks(...)` list
construction.

Note this requires an *established Signal session*, so it is a malicious-contact attack, not an
unauthenticated one. It is still a real denial-of-service.

**Where:** `node-daemon/.../session/DefaultFileTransferHandler.java` `onFileOffer` / `onFileChunk`.

### 🟠 C-4. Filename sanitizer does not stop `..`

```java
private static String sanitizeFileName(String fileName) {
    return fileName.replace("/", "_").replace("\\", "_");
}
```

This is currently *sufficient* — stripping both separators means `..` cannot form a traversal
sequence — and the Javadoc is honest that it is narrow. It is flagged because it is **fragile by
construction**: it is a denylist guarding a `resolve()` call, and the moment any caller uses it
differently (or a subdirectory feature is added), it breaks. `M7` will add a real "save as"
path chooser, which is exactly when this gets misused.

Replace with an allowlist + post-resolve containment assertion (`normalize().startsWith(dir)`).

**Where:** `node-daemon/.../session/PrintingDaemonEventListener.java`.

### 🟢 Audited and found *correct* — no action needed

Recorded so the next audit does not redo the work:

- **Length-prefix bounds checks are present and correct** in all four hand-rolled codecs
  (`ChatMessageCodec`, `FileTransferMessageCodec`, `RelayFrameCodec`, `DiscoveryFrameCodec`).
  Each validates `length < 0 || length > remaining()` before `new byte[length]`. The earlier
  unchecked-allocation gap was genuinely fixed. `FileTransferMessageCodec` also bounds its
  chunk-index count against `remaining() / 4`, which is the right check.
- **`DiscoveryRecordCodec` is well-hardened**: `MAX_ADDRESSES`, `MAX_ADDRESS_LENGTH`,
  `MAX_BUNDLE_LENGTH` caps, plus an `available()` check in `readBytes`.
- **`verifyAndDecode` checks in the correct order** — parse, then signature, then peer-ID
  binding, then expiry — and `ContactService` calls that safe entry point (not the unverified
  `decode`), passing the expected peer ID. The M6f MITM defence is real and correctly wired.
- **`SessionManager`'s dedup/HLC-drift gate ordering** is correct: `checkDrift` before dedup
  before persistence.

---

## 2. Track A — Make the relay a real transport (**blocking M6h**)

### Rationale, including a correction

An earlier verbal recommendation in this project's discussion was to spike NAT traversal *before*
M6h. **That was wrong, and this document supersedes it.** DCUtR-style hole punching operates
*over* an existing relayed connection: the two peers exchange observed external addresses and
synchronise a simultaneous dial through the relay. A working, persistent, bidirectional relay
session is therefore a **hard prerequisite** for hole punching, not an alternative to it.

Relay-first is the correct dependency order. It is also what every comparable production system
does — Signal, WhatsApp, Tailscale (DERP) and every WebRTC deployment (TURN) all ship a relay and
all keep a permanent fraction of users on it. A relay is not a failure state.

These four items are grouped as Track A because each one changes what `DaemonMain` must own. Doing
M6h first means writing the composition root, then rewriting it.

### A-1. Persistent relay session (fixes findings A-1 and A-2)

**What.** Introduce a `RelaySession` abstraction owned by the daemon, not created per-send:

- One long-lived connection to the configured relay, established at daemon start.
- `RelayController` gains `close()`; `RelaySession` gains lifecycle (`connect`/`close`/`isConnected`).
- Reconnect with exponential backoff + jitter (cap ~30s), because home NAT mappings and relays
  both drop connections routinely.
- Application-level keepalive. This is not optional: NAT mapping timeouts for TCP are commonly
  as low as 5–15 minutes, and without keepalive the daemon will believe it is reachable when it
  is not. Add a `PRESENCE_PING` frame type — note `EnvelopeType` already reserves `9` for exactly
  this.
- `ConnectionStrategy` takes the existing session instead of dialling; it must no longer call
  `connectToRelay` itself.

**Done when.** A daemon holds one relay connection across ≥30 minutes idle, survives a relay
restart via automatic reconnect, and `lsof`/`ss` shows connection count flat (not growing) after
1,000 relayed sends.

*Status (2026-09-16):* ✅ **Completed & Verified.** Implemented via `RelaySession` (`connect`, `close`, `isConnected`), keepalive PING/PONG with nonce tracking, exponential backoff with jitter, and `Libp2pNetworkService.connectToRelay` fixed to bind caller's `RelayBinding` directly onto the stream. Empirically verified on real hardware via 3-terminal matrix (`relay-server`, `client1`, `client2`): keepalive PING/PONG without timeout, end-to-end message delivery through relay, and unattended reconnect upon relay server restart with post-reconnect delivery confirmed.

### A-2. Relay-delivered inbound reception (fixes finding A-1)

**What.** Wire `SessionManager` as a real `RelayEventHandler`. An inbound `DELIVER` frame must
enter the *same* pipeline as an inbound Envelope: `EncryptedFrameCodec.decode` → `sessions.decrypt`
→ `handleDecryptedPlaintext`. Route it onto `inboundExecutor` so the single-threaded dedup
guarantee documented in `SessionManager`'s Javadoc still holds.

Also fix the reply path: `handleChatMessagePayload` currently sends its delivery receipt with
`outbound.send(chat.senderAddress(), null, ...)` — a hardcoded `null` relay address. A peer
reachable only by relay will never receive a receipt. The relay address must be threaded through.

**Done when.** Two daemons, both with unreachable direct addresses, exchange chat messages **and
delivery receipts** in both directions through a relay. This is the first genuine cross-NAT
message exchange in the project's history and deserves its own README checkpoint.

### A-3. Relay store-and-forward (fixes finding A-3)

**What.** Promote from *Post-M6* to now. Give `relay-server` a durable spool:

- SQLite (reuse `core-storage`'s migration runner) — not in-memory.
- Queue on `no route to peer`; flush on `onConnected`.
- **TTL** (suggest 7 days) and a **per-peer quota** (count + bytes) — both mandatory, or the
  relay is a free unbounded disk for anyone.
- Delivery is at-least-once; dedup already exists on the receiving side (`storage.hasMessage`),
  which is precisely why at-least-once is safe here.

**Privacy note worth writing down:** the relay sees ciphertext only, but it *does* see the social
graph (who talks to whom, when, how much). Persisting spooled messages makes that graph durable
rather than transient. State this in the spec's threat model rather than leaving it implicit.

**Done when.** Peer A sends to offline Peer B; B starts an hour later and receives it. Expired
messages are provably purged. Quota rejection is exercised by a test.

*Status (2026-09-27):* ✅ **Completed & Verified.** Implemented via `RelaySpool` / `SqliteRelaySpool` (thread-confined single-worker DAO, deterministic `purgeExpired`, 7-day TTL, per-peer quotas of 500 messages / 50 MB, 64 KiB frame ceiling), independent `relay-spool.sqlite` schema and migration runner, and wired into `RelayRegistry` (enqueue-on-unregistered with quota rejection handling, disconnect-guarded FIFO drain upon reconnection, startup cold-purge sweep). Unit tests cover all quotas, TTL cutoff boundaries, mid-drain disconnect safety, and FIFO delivery. Empirically verified on real hardware via full 3-process asynchronous lifecycle matrix (`relay-server`, `alice`, `bob`): offline spooling of PQXDH PreKey messages and Signal Double Ratchet frames, reconnection FIFO flush, and full round-trip delivery receipt delivery while the originating sender was offline, with Alice's local SQLite database confirmed flipping from `SENT` to `DELIVERED` and relay spool draining to 0 rows.

*Follow-ups (2026-10-02):* A-3 stands for the checkpoint above. A second-pass read found three
gaps beyond it: the spool entry is deleted right after `send()` rather than after the recipient
acknowledges it (**A-3b**, = F-8c); a live frame can overtake a draining backlog (**A-3c**,
= F-8d); and the per-target quota is not a bound on its own, because target IDs are free to mint
(**A-3d**, = F-7a). Tracked in §11 and §12; Track A's checkpoint is not reopened.

### A-4. Relay configuration & selection

**What.** The daemon needs to *know* its relay. Today relay addresses arrive as CLI args on demo
Mains. `DaemonMain` needs a config file (`~/.p2p-chat/config.json` or similar) with a bootstrap
relay list, plus persistence of which relay this node is registered with — the invite-code `d`
field and `DiscoveryRecordV2.relayMultiaddr` both already anticipate this.

**Done when.** A daemon starts with no CLI arguments and connects to its configured relay.

*Update (2026-10-02):* **Open — expanded in §11 into A-4a…A-4g.** The text above is unchanged and
its *Done when* is retained as acceptance criterion 1. The additions: a versioned config schema
(A-4a); a defined relay selection, persistence and failover rule (A-4b); bounded, non-blocking
startup (A-4c); the invite code's `d` field and publication of this node's own record, including
a trap in `PreKeyBundleFactory.create` (A-4d); the `DaemonMain` first slice and lifecycle (A-4e);
status exposure (A-4f); and eight acceptance criteria (A-4g).

---

## 3. Track B — Protocol versioning (**blocking M6h**)

Design Principle #3 in `architecture-spec.md` states:

> *"Version everything from day one. Every wire message carries a `protocol_version`... adding
> new message types or fields later is additive, not breaking."*

`grep -rn "protocol_version|protocolVersion|PROTOCOL_VERSION" --include="*.java"` returns
**zero results**. The `Envelope` shell that was to carry it was never built; the implementation
notes concede this field-by-field as each element migrated into individual payloads.

So the one mechanism the spec designates as the guarantor of "no drastic changes later" is the
only major spec element that does not exist. The cost of adding it is one byte per codec today.
After M7 ships to a second machine, it is a coordinated flag-day migration.

### B-1. Version byte in every hand-rolled codec

**What.** Prefix each of the four codecs with a format version byte. `DiscoveryRecordCodec`
already has `FORMAT_MARKER` — follow that precedent exactly. Decoders reject unknown versions
with a distinct, catchable exception (not the generic malformed error), so a future daemon can
tell "corrupt" from "too new."

**Do this before A-1/A-2/A-3 land**, so the new relay frames are versioned from birth.

### B-2. Capability negotiation at session start

**What.** A `HELLO`-style capability exchange listing supported features
(`relay-spool`, `file-transfer`, `groups`, `mls`, …). `EnvelopeType` reserves `HANDSHAKE_INIT`/
`HANDSHAKE_RESPONSE` (`0`/`1`) which `ApplicationMessageRouter` currently treats as
routing errors — that is the natural home.

This is what makes M8 (groups) additive rather than breaking, and it is far cheaper to add now
than to retrofit.

### B-3. Write down the compatibility policy

One short ADR: what a node does with an unknown message type (ignore + log), an unknown field
(ignore), a newer major version (refuse with a clear user-facing error). Without a written
policy, each future decision gets re-litigated.

---

## 4. Track C — Security hardening

### C-1. Bind the WebSocket to loopback and authenticate it 🔴 **do first**

Three changes, all small, all necessary:

1. **Bind loopback.** `bootstrap.bind("127.0.0.1", port)`. One-line fix that removes LAN exposure.
2. **Check `Origin`.** Reject any handshake whose `Origin` header is present and not an allowed
   value. This is the only defence against a malicious website connecting; browsers will not do
   it for you.
3. **Require a token.** Generate a random token at daemon start, write it to
   `~/.p2p-chat/rpc-token` with `0600`, require it as a query parameter or first-message
   handshake. This is how Jupyter, Docker Desktop and every well-behaved local daemon do it, and
   it is what stops *other local processes* (any npm postinstall script, any browser extension
   with native messaging) from driving the API.

**Done when.** A connection from a non-loopback address is refused; a connection with a wrong or
absent token is refused; the Electron client (M7) authenticates successfully by reading the
token file.

### C-2. Restrict key file permissions 🔴

`Files.write(..., PosixFilePermissions)` at creation (not `chmod` after — that leaves a race
window), for `identity.key`, the Signal identity key, and the SQLite database (which holds
ratchet state and plaintext message history). On Windows, fall back to an ACL or accept and
document the gap. Keychain integration remains the pre-release goal; this is the interim fix.

### C-3. Validate all wire-supplied sizes and indices 🔴

In `FileOfferPayload` handling: bound `chunkSize` (e.g. 1 KiB–8 MiB), `totalChunks`, and
`fileSize`; assert `ceil(fileSize / chunkSize) == totalChunks` for internal consistency. In
`onFileChunk`: reject `chunkIndex < 0 || chunkIndex >= totalChunks` **before** the `seek`, and
reject a plaintext chunk longer than `chunkSize`.

Add a configurable maximum accepted file size, and surface offers over it to the UI rather than
silently accepting.

### C-4. Harden the filename sanitizer 🟠

Allowlist characters, strip leading dots, cap length, reject Windows reserved names
(`CON`, `PRN`, `AUX`, `NUL`, `COM1`…), and — most importantly — assert containment after
resolution:

```java
Path resolved = downloadDir.resolve(name).normalize();
if (!resolved.startsWith(downloadDir.normalize())) throw new SecurityException(...);
```

Also handle collisions (`file (2).txt`) rather than overwriting.

### C-5. Adversarial codec test suite ⭐ **highest-value test work in the project**

Every hand-rolled codec parses attacker-controlled bytes. That is the primary remote attack
surface. The existing tests are round-trip tests — they prove the codec agrees with itself, which
is exactly the property an attacker does not care about.

Add, per codec: truncation at **every** byte offset; negative lengths; `Integer.MAX_VALUE`
lengths; length larger than remaining; unknown/reserved markers; empty input; invalid UTF-8;
deeply nested/oversized JSON for `JsonCodec`. Property-based testing (jqwik) is a good fit; a
seeded random fuzzer run in CI is the cheap version.

**Done when.** No malformed input to any codec produces anything other than the codec's declared
exception type — specifically no `OutOfMemoryError`, no `NegativeArraySizeException`, no
unbounded allocation, no infinite loop.

### C-6. Write down the threat model

`architecture-spec.md §8` covers crypto choices but there is no explicit threat model. Name the
adversaries and what is and is not defended: malicious relay (sees graph + timing, not content),
malicious contact (C-3's DoS class), LAN attacker (C-1), local malware (C-2), network observer,
and the explicitly out-of-scope ones (global passive adversary, traffic analysis, compromised
endpoint). This directly informs M8's group-membership design.

---

## 5. Track D — Verification infrastructure & hygiene

### D-1. `LICENSE` file 🔴 legal inconsistency

There is **no `LICENSE` file** in the repository. Absent one, default copyright applies — all
rights reserved — which contradicts both the stated intent to open-source and the fact that the
project links AGPL-3.0 `libsignal-client`. Add `LICENSE` (AGPL-3.0) and an SPDX header
convention. Five-minute fix; currently a real inconsistency.

### D-2. Fix CI

`.github/workflows/ci.yml` triggers only on `main`/`master`/`develop`. All feature-branch work —
including this plan's — runs **no CI at all**. Change to run on all branches and all PRs. Add
JaCoCo (there is currently no coverage tooling, so the true coverage number is unknown), publish
the report, and set a floor that ratchets upward rather than a fixed target.

### D-3. Resolve the verification-status contradiction

`README.md`'s M6g-4 section claims *"39/39 Gradle tasks green repository-wide."*
`docs/M6g-gap-analysis-and-plan.md:368` states *"none of its new tests have actually been
executed."* Both cannot be true.

Per the project owner: **everything through M6g-4 has been fully verified on live hardware,
including the libp2p and libsignal paths.** The gap-analysis line is the stale one. Correct it,
and remove the now-obsolete first bullet of that document's §5 ("A real `./gradlew test`
confirmation of M6g-4 itself, before anything else").

Then adopt a **single verification vocabulary**, defined once and used everywhere:

| Badge | Meaning |
|:---|:---|
| ✅ **Verified (hardware)** | Executed on real hardware, real dependencies. Date + method recorded. |
| 🧪 **Tested (CI)** | Automated tests pass in CI. |
| 🔨 **Compiles** | Builds; not executed. |
| ✍️ **Reviewed** | Read carefully; not executed. |

Add `Verified: <date> — <how>` to each milestone. The credibility problem was never the
engineering; it is that a reader cannot currently distinguish these four states.

### D-4. Adopt real commit hygiene

The entire 19,000-line, 16-milestone history is **one commit**. That forecloses `git bisect`,
makes review impossible, and is why the verification claims cannot be independently checked.

From here: one commit per logical change, Conventional Commits, tag each milestone
(`v0.6.4-m6g-4`). Do not attempt to retrofit history — start now.

### D-5. Fill the test-category gaps

Beyond C-5 (adversarial codecs), the suite is strong on isolated primitives and absent on
system-level properties:

- **Crash recovery** — `SIGKILL` mid-transaction; reopen; assert invariants. Non-negotiable given
  resumable transfers and a persistent Double Ratchet.
- **Multi-peer concurrency** — N peers messaging one `SessionManager` simultaneously; assert no
  cross-session leakage and no dedup violations. This is the core claim of M6e-2.
- **Relay failure injection** — kill the relay mid-send; assert reconnect and no message loss.
- **Cross-process E2E** — two real daemons over real sockets, driven by JSON-RPC, asserted
  programmatically. Currently a human reads two terminals.

### D-6. Operational basics for a long-running process

M6h introduces the project's first genuinely long-lived process. It needs: a real logging
framework (SLF4J + Logback) replacing ~100 `System.out.println` calls, with levels and rotation;
graceful shutdown hooks that flush and close cleanly; and a health/status endpoint. Also audit
for unbounded growth — `outgoingTransfers` has TTL eviction, but `incomingTransfers` and the
`PeerRoutingTable` should be checked.

---

## 6. Track E — Documentation restructure

The goal is **relocation, not deletion**. Nothing is lost; the front door stops being 167 KB.

Current state: 3,672 comment lines against 8,001 code lines in `main/`; a 991-line README;
Javadoc blocks running 80+ lines before a class declaration.

### E-1. Target structure

```
README.md                      ~150 lines: what it is, quickstart, status table, links out
docs/architecture-spec.md      the contract: what the system IS (implementation notes moved out)
docs/threat-model.md           new — see C-6
docs/adr/NNNN-*.md             one decision per file: Context / Decision / Consequences
docs/milestones/M0..M6g.md     the dev-log narrative, preserved verbatim, archived
docs/roadmap.md                the Feature Tracking Matrix, single source of truth
```

### E-2. ADRs to extract first

The highest-value candidates already exist as prose and only need relocating: single-threaded
inbound executor; Netty for WebSocket; hand-rolled JSON; libsignal vs. own crypto (+ AGPL);
`PeerRoutingTable` in `node-daemon`; libp2p peer ID as canonical identity; no in-memory cache
over `StorageService`; relay as trust-boundary-excluded.

### E-3. Javadoc policy

Explain the non-obvious *why*; **link** the argument rather than restating it
(`@see docs/adr/0007`). Delete comments that argue with a hypothetical reviewer.

The concrete tell that this has gone too far: `deriveDirectConversationId` is now duplicated in
three classes, each copy carrying multi-paragraph justification — including one that concedes the
original rationale "does not strictly apply" and retains the duplication for consistency. When
the defence of a decision is longer than the fix, extract the five-line helper.

---

## 7. Recommended sequence

**Phase 0 — hygiene (½ day).** D-1 `LICENSE`, D-2 CI + JaCoCo, D-3 verification vocabulary,
D-4 commit hygiene. Do this first so everything after it is covered by CI and honestly recorded.

**Phase 1 — urgent security (1–2 days).** C-1 WebSocket loopback + Origin + token, C-2 key
permissions, C-3 size validation, C-4 sanitizer. C-1 is the single most important item in this
document.

**Phase 2 — versioning (1–2 days).** B-1 version bytes, B-2 capability handshake, B-3 policy ADR.
Before Track A, so new relay frames are versioned from birth.

**Phase 3 — relay as real transport (1–2 weeks).** A-1 persistent session, A-2 inbound reception,
A-3 store-and-forward, A-4 configuration. Checkpoint: two daemons, both NAT-bound, exchanging
messages and receipts in both directions, including while one is offline.

> **Amended (2026-10-02):** Phase 3 is refined into sub-phases 3.0–3.5, and Phases 4–5 gain work,
> in **§14** (with a dependency table). Phases 0–2 are unchanged and already complete. A-4 is
> expanded in §11.

**Phase 4 — verification (ongoing, start in Phase 1).** C-5 adversarial codec fuzzing,
D-5 crash/concurrency/E2E, D-6 logging and shutdown.

**Phase 5 — M6h `DaemonMain`.** Now a genuine composition root: config, relay session, RPC token,
versioned protocol, logging, graceful shutdown, and the inherited backlog (pre-key refresh
cadence, file-transfer completion receipt, live send-failure events, outbound read receipts).

**Phase 6 — NAT traversal programme.** In strict ROI order:
1. **IPv6** — many home ISPs now provide globally routable IPv6 with a stateful firewall and no
   NAT. Nearly free; try it first.
2. **UPnP-IGD / NAT-PMP / PCP** — ask the router for a real port mapping. No hole punching
   required, plain SSDP + SOAP, works on a large share of consumer routers. Best ROI on the list.
3. **Observed-address discovery** — the relay already sees each peer's source `ip:port`; have it
   report that back. This is most of what STUN does, using infrastructure already deployed.
4. **TCP simultaneous-open hole punching (DCUtR-style)** — the ambitious one. Set expectations
   honestly: TCP hole punching succeeds roughly 20–40% in the wild versus 70–90% for UDP.
5. **QUIC/UDP transport** — the industrially correct answer and jvm-libp2p's weakest area.
   Likely means contributing QUIC upstream or writing a custom transport. Name it now as a
   strategic fork; decide it after (1)–(4) have set the real direct-connection rate.

Instrument the connection-path outcome (DIRECT / RELAYED / UNREACHABLE) from the start, so this
programme is driven by measured success rates rather than intuition.

**Phase 7 — M7 UI.**

**Phase 8 — M8 groups.** Two notes worth carrying forward. First, the schema already anticipates
this: `conversation_members` and `crdt_ops_log` exist in `V001`, and `SqliteSignalProtocolStore`
already implements `SenderKeyStore` (stubbed). Second, and more important: **the hard problem is
not the CRDT.** OR-Set merge is well-documented and tractable. The hard problem is *membership
authorisation without a server* — who may add or remove whom, and how do peers agree when a
malicious member can forge operations. Signal solves this with server-held zero-knowledge group
credentials; there is no server here. Research **MLS (RFC 9420)** against sender-keys before
writing any `core-groups` code: MLS provides proper group key agreement with forward secrecy and
post-compromise security, and is where the industry is converging. Sender-keys is simpler and
already in libsignal. That trade-off deserves its own ADR before implementation.

---

## 8. Priority summary

| # | Item | Track | Severity | Blocks M6h |
|:--|:---|:---|:---|:---|
| 1 | WebSocket loopback + Origin + auth token | C-1 | 🔴 Critical | Recommended |
| 2 | Key file permissions `0600` | C-2 | 🔴 High | No |
| 3 | Wire-supplied size/index validation | C-3 | 🔴 High | No |
| 4 | `LICENSE` file | D-1 | 🔴 Legal | No |
| 5 | Protocol version byte + capabilities | B-1/B-2 | 🟠 Structural | ✅ **Done** |
| 6 | Persistent relay session + `close()` | A-1 | 🔴 Structural | ✅ **Done** (hardware verified) |
| 7 | Relay inbound reception | A-2 | 🔴 Functional | ✅ **Done** (hardware verified) |
| 8 | Relay store-and-forward | A-3 | 🟠 Functional | **Yes** |
| 9 | Relay configuration | A-4 | 🟠 Functional | **Yes** |
| 10 | Adversarial codec tests | C-5 | 🟠 High value | No |
| 11 | CI on all branches + JaCoCo | D-2 | 🟠 Process | No |
| 12 | Verification vocabulary + doc fix | D-3 | 🟠 Credibility | No |
| 13 | Commit hygiene | D-4 | 🟠 Process | No |
| 14 | Filename sanitizer hardening | C-4 | 🟡 Medium | No |
| 15 | Threat model document | C-6 | 🟡 Medium | No |
| 16 | Logging / shutdown / health | D-6 | 🟡 Medium | Recommended |
| 17 | Crash + concurrency + E2E tests | D-5 | 🟡 Medium | No |
| 18 | Documentation restructure | E | 🟡 Maintainability | No |
| 19 | Shared DB connection: lock + re-entrant transactions (🔬 reproduced; implemented, awaiting Gradle run) | F-1 | 🔴 Critical | **Yes** |
| 20 | Per-peer crypto serialization (🔬 reproduced 2026-10-03) | F-2 | 🔴 High | **Yes** |
| 21 | Length-prefixed framing, stream close (3a, 3b 🔬 reproduced), chunk ceiling | F-3 | 🔴 High | **Yes** |
| 22 | Receipt authorization + monotonic delivery state | F-4 | 🟠 High | No |
| 23 | Signal ↔ peer-ID identity binding | F-5 | 🟠 High | Before first external build |
| 24 | Peer-supplied address dial policy | F-6 | 🟡 Medium | No |
| 25 | Relay + receiver resource bounds (mitigation) | F-7 (= A-3d) | 🟠 High | Before any public relay |
| 26 | Outbox, retry, ack-based spool deletion + `STORED` ack, FIFO drain | F-8 (= A-3b/c) | 🟠 Functional | **Yes** (inherited M6h backlog) |
| 27 | Pre-key pool, rotation, first-send refresh, record freshness (`sequence`) | F-9 | 🔴 Functional | **Yes** |
| 28 | Path memory, address scope, relay-mismatch detection | F-10 | 🟠 Functional | **Yes** (part of A-4) |
| 29 | API / lifecycle defects (first run, conversations, history, Origin, status, error semantics) | F-11 | 🟠 Functional | **Yes** |
| 30 | RPC handlers off the Netty I/O thread | F-12 | 🟠 Functional | **Yes** |
| 31 | Smaller defects (empty files, `identity.meta`, migration splitter, HLC lower bound, chunk sender check, GCM AAD) | F-13 | 🟡 Medium | No |
| 32 | A-4 expanded: config, selection, async startup, discovery seam + publisher, entry point, acceptance | A-4a–g | 🟠 Functional | **Yes** |
| 33 | Format-break window + ADR-0002 | §10.5 | 🟠 Structural | **Yes** (before first external build) |

---

## 9. Closing note

The engineering in this repository is strong: real libsignal rather than hand-rolled crypto,
correct and well-reasoned concurrency, forward-only migrations, genuine module boundaries, and a
signed-discovery-record implementation that closes a real MITM vector and is verified against the
official libp2p test vector.

The findings above are not a verdict on that work. They cluster in a specific and predictable
place — **the seams between proven components**, and the transition from one-shot demos to a
long-running daemon exposed to a network. That is exactly the territory M6h enters, which is why
these belong before it rather than after.

The single most consequential correction in this document is the relay one: a relay is not a
lesser substitute for NAT traversal, it is the substrate hole punching runs on. Getting it right
first is both the correct dependency order and the fastest path to the project's real milestone —
**two users on different networks, talking to each other.**

---

## 10. Second-pass audit (2026-10-02) — overview

§1–§9 above are the **first audit pass** and are left exactly as written. This section opens the
**second pass**, which re-read the codebase at its current state (HEAD `194da04`, *"Wire RelaySpool
into RelayRegistry and verify Track A-3"*) after Track A-1…A-3 landed. §9's closing note describes
the first pass; read §10–§16 as its continuation. Where the two disagree, this pass governs, and
every disagreement is listed in §10.3.

The second pass produced: an expanded specification for **A-4** (§11); thirteen new work items,
**Track F** (§12); a **decision register** for the choices that need a human (§13); a **revised
sequence** with a dependency table (§14); the **two stress tests the project owner is running**
(§15); and a short list of observations that are recorded but deliberately **not scheduled** (§16).
Nothing found in the second pass is omitted from this document.

### 10.1 Baseline and scope

| Measure | Value at HEAD `194da04` |
|:---|:---|
| Tests (from the project's own JUnit XML, 2026-09-22 → 2026-09-27) | 427 run · 4 skipped · 0 failed — matches the README |
| Instruction coverage (JaCoCo) | `core-model` 100 · `core-messaging` 96.9 · `core-discovery` 93.8 · `core-crypto` 91.4 · `core-filetransfer` 89.2 · `core-identity` 83.6 · `core-storage` 81.2 · `relay-server` 73.7 · `core-network` 47.3 · `node-daemon` 39.4 |
| Why `node-daemon` reads low | 3,637 lines of demo `*Main` classes at 0%; the real logic is `SessionManager` 81%, `JsonRpcRouter` 78% |
| Automated tests that start a real libp2p host | **None** — `Libp2pNetworkService` is 0% covered; every network path is hardware-verified only |
| Automated tests that cover concurrency of `SqliteStorageService` or crypto interleavings in `SessionManager` | **None** |

**Read in full:** all docs; `core-model`, `core-identity`, `core-crypto`, `core-discovery`,
`core-storage`, `core-messaging` (HLC), `core-filetransfer` (chunker/cipher), `core-network`
(connection strategy, relay session, protocols, network service), `relay-server` (registry,
discovery registry, entry point), and in `node-daemon`: `SessionManager`,
`SqliteSignalProtocolStore`, `SynchronizedSignalProtocolStore`, `JsonRpcRouter`, the WebSocket
server and auth handler, `ContactService`, `InviteCodeCodec`, `DefaultFileTransferHandler`.

**Not read:** the 26 demo `*Main` classes, `JsonCodec` internals, `SqliteRelaySpool`'s code (its
constants and behaviour were taken from the README and A-3's status line), and individual test
bodies. **Not executable in the audit environment:** native `libsignal` and `jvm-libp2p`, so
nothing about crypto or live-network behaviour was run except as stated in the evidence tags.

### 10.2 Evidence tags (used only in §10–§16)

These extend the four-badge vocabulary of D-3 for findings that are not milestones.

| Tag | Meaning |
|:---|:---|
| 🔬 **Reproduced** | Executed in the audit sandbox against the project's own compiled classes. Not yet a CI test; dependency versions may differ from the pinned ones (stated per item). |
| 📖 **Source-confirmed** | The upstream library's source or documentation was read at the stated version. The failure itself was not executed. |
| ✍️ **Reviewed** | Read carefully in this repository; not executed. (Same meaning as in D-3.) |
| ⏳ **Unconfirmed** | A hypothesis. The fix is specified, but its *priority* depends on a stress test (§15) the project owner is running. |

### 10.3 Amendments to the first pass

| First-pass statement | Second-pass position |
|:---|:---|
| §9: *"correct and well-reasoned concurrency"* | **Amended.** The single-threaded `inboundExecutor` reasoning is sound for the inbound path. The gap is that other threads (RPC, outbound, scheduler, libp2p callbacks) touch the same database connection and the same ratchet state. See F-1, F-2. |
| A-3 *"Completed & Verified"* | **Stands for its stated checkpoint** (happy-path lifecycle, hardware-verified). Three follow-ups are tracked: A-3b ack-based deletion (= F-8c), A-3c FIFO during drain (= F-8d), A-3d global and per-sender caps (= F-7a). Track A's checkpoint is not reopened. |
| §1 *"Audited and found correct"* list | **Stands.** No second-pass finding contradicts it. |
| C-3 size/index validation | **Stands.** F-13a records one edge it does not cover (zero-byte files). |
| A-4 as written (config file + bootstrap list) | **Expanded** into A-4a…A-4g (§11); the original *Done when* is retained as acceptance criterion 1. |

### 10.4 Cross-cutting rules introduced by the second pass

These are invariants that several Track F items depend on. They are written once here so each
item can cite them.

**R-1 — Lock hierarchy.** Acquire in this order only, never the reverse:
`peerCryptoLock(peer)` (F-2) → `SynchronizedSignalProtocolStore` monitor (exists) →
`SqliteDatabase` lock (F-1). Never call the network, a `DaemonEventListener`, or any other
blocking operation while holding the database lock. Transaction bodies call storage only — never
crypto, never network.

**R-2 — Thread ownership.** Netty I/O threads (WebSocket workers, libp2p) only read and write
bytes and hand work off. They never run crypto, SQL, file I/O or hashing. Every executor has one
owner and one purpose:

| Executor | Purpose | Status |
|:---|:---|:---|
| Netty I/O (WebSocket workers, libp2p) | move bytes; enqueue | exists |
| `rpcExecutor` | `JsonRpcRouter` dispatch | **new — F-12** |
| `inboundExecutor` | decrypt + dispatch inbound frames, single-threaded | exists |
| `outboundExecutor` (`OutboundMessageService`) | sends | exists |
| `eventExecutor` | `DaemonEventListener` callbacks | exists |
| scheduler(s) (`RelaySession`, outbox, publisher, prekey manager) | timers | partly new — every blocking call on them needs an explicit timeout |

**R-3 — Authenticated attribution.** Anything a peer puts *inside* a message — a `conversationId`,
a `messageId`, a `senderAddress`, a peer ID — is a **claim**, not a fact. Derive it from the
authenticated sender (Noise-authenticated stream identity, or relay attestation, which is weaker —
see F-5) or validate it against local state before acting on it.

**R-4 — Wire changes go through the format-break window (§10.5) or an ADR-0001 version bump.**
Never ship a silent wire change.

**R-5 — A fix lands with the regression test that fails on the pre-fix code.** Record
red → green in the commit message. The F-1 experiments are the seed for that practice.

### 10.5 The format-break window

ADR-0001, Category 2 records that all four hand-rolled codecs are **fixed positional binary
formats with no extensible trailing field**. Consequence: every field added to a codec is a
Category 3 change — a version bump that older peers *refuse*. That is cheap today and a flag day
after a second machine runs the project. (**Assumption to confirm:** no build has been given to
anyone outside the project owner's own machines.)

So the wire/format/persisted-schema changes found by the second pass are **batched into one
deliberate window**, documented in a new **ADR-0002 "Pre-release format reset"**, and closed when
the first build leaves the owner's machines (the first external M7 run at the latest).

| Item | Change in the window |
|:---|:---|
| F-3a | Length-prefixed framing; protocol IDs `/p2p-chat/{envelope,relay,discovery}/0.1.0` → `0.2.0` |
| F-5 | Identity-binding field in handshake init/response **and** in the chat payload (codec version bumps) |
| F-8c | Relay `DELIVER` carries a spool ID; new relay `ACK` frame (client→relay) and `STORED` frame (relay→sender, DR-10) (relay protocol `0.2.0`) |
| F-9 | New discovery-record body carrying a pre-key *pool*, plus a signed monotonic `sequence` and `issuedAt` (record format bump) |
| F-13g | AES-GCM chunk AAD (chunks from older builds no longer decrypt) |
| A-4a | `config.json` schema, `configVersion: 1` |
| DR-7 | Every bumped codec gains a trailing length-prefixed **extensions** block, so Category 2 (unknown field → ignore) becomes real and future additions stop being breaking. Extensions lie **inside the signed bytes** wherever a codec is signed; a type ≥ 0x8000 is *critical* (reject if unknown) |

---

## 11. Track A — expanded: A-4 and the A-3 follow-ups

### A-3 follow-ups (status: open; tracked in Track F)

| ID | What | Canonical text |
|:---|:---|:---|
| A-3b | Delete a spooled message only after the recipient acknowledges it | F-8c |
| A-3c | Preserve FIFO per target while a backlog drains | F-8d |
| A-3d | Global and per-sender spool caps (per-target quota alone is not a bound) | F-7a |

**Recommended Track A closure criteria:** A-4 acceptance criteria (below) met, **and** A-3b and
A-3d either implemented or explicitly deferred in ADR-0002 with a stated reason.

### A-4. Relay configuration & selection — expanded specification

The original A-4 (the *What* / *Done when* in §2) stays as written. It specifies a config file, a
bootstrap relay list, and persistence of the registered relay. Reading the code against it shows
that "configuration" is the smallest part of A-4. The rest is **selection**, **startup
behaviour**, **the invite code's `d` field**, and **who publishes this node's own discovery
record** — none of which exists in the daemon path today. A-4 is therefore split into seven parts.

**A-4 delivers the first slice of `DaemonMain`:** config-driven startup through relay connection
and record publication. M6h (Phase 5) adds the RPC server wiring, logging, and shutdown to the
same entry point. This is what makes the original *Done when* demonstrable.

#### A-4a. Versioned configuration file

**What.** A `DaemonConfig` loaded from `<dataDir>/config.json` (default `dataDir`: `~/.p2p-chat`,
as in the original A-4; platform equivalents documented for Windows). Parsed with the existing
`JsonCodec` — no new dependency. Written once with defaults on first run; never rewritten by the
daemon afterwards (daemon-written state lives elsewhere — A-4b).

```json
{
  "configVersion": 1,
  "dataDir": null,
  "network": { "listenPort": 9300 },
  "relays": [ { "multiaddr": "/ip4/203.0.113.7/tcp/9100/p2p/12D3Koo..." } ],
  "rpc": { "port": 9400, "tokenFile": null, "allowedOrigins": [] },
  "files": { "downloadDir": null, "maxAcceptedFileSizeBytes": 2147483648 },
  "discovery": { "recordTtlHours": 24, "refreshHours": 6 }
}
```

`null` means "derive the default". Precedence: **CLI flag > environment variable
(`P2PCHAT_CONFIG`, `P2PCHAT_DATA_DIR`) > file > default.** Restart required to apply changes
(no hot reload in v1). The existing `p2pchat.dataDir` system property stays for the demo `Main`s.

Validation, all at load time with an error that names the offending key:

- every relay `multiaddr` must contain a `/p2p/<peer-id>` component — `RelaySession` only discovers
  its absence inside `extractRelayPeerId`, long after startup;
- ports in range; `allowedOrigins` entries are well-formed origins;
- **unknown keys:** ignore and warn (ADR-0001 Category 2 policy);
- **`configVersion` newer than this build:** refuse with a clear error (Category 3 policy);
- **no relays configured:** *not* an error — the daemon starts in direct-only mode and says so.

**Why.** Today relay addresses arrive as positional CLI args on demo `Main`s; there is no config
mechanism anywhere outside them. `rpc.allowedOrigins` is needed by F-11d, `files.*` by C-3's
"configurable maximum", `discovery.*` by A-4d.

**Where.** New package `com.p2pchat.daemon.config` (`DaemonConfig`, `ConfigLoader`).

#### A-4b. Relay selection, persistence and failover

**What.** A `RelayManager` owns the relay lifecycle; `RelaySession` stays single-relay and
unchanged.

1. **Choose one active relay** from `relays`: try the persisted active relay first, then list
   order, each with a bounded connect timeout (default 5 s).
2. **Persist the choice** in a daemon-written store, *not* in the user-edited config. Recommended:
   a `daemon_state(key, value)` table via migration `V005` (atomic, transactional — DR-8).
3. **Fail over** to the next candidate after the active relay has been continuously unreachable
   for `failoverAfterSeconds` (default 180). On a switch: close the old session, open the new one,
   **republish the discovery record** with the new `relayMultiaddr` (A-4d), and emit
   `event.network.statusChanged`.
4. **v1 limitation, stated plainly:** contacts must share a relay. With a `RelaySession` present,
   `ConnectionStrategy` ignores a contact's own `relayMultiaddr`. v1 detects this and reports
   `RELAY_MISMATCH` — at `contacts.add` and at send — instead of failing silently (F-10c, DR-2). The bootstrap list exists for
   *this node's* failover, not for reaching contacts on other relays.

**Why.** The original A-4 says "bootstrap relay list" without saying what a list *means*. Without
a selection rule, failover silently invalidates every contact's stored `relay_multiaddr` and this
node's own published record.

**Where.** New `com.p2pchat.daemon.relay.RelayManager`; `SessionManager.start` (line 269) takes
the manager's session rather than a raw multiaddr string.

#### A-4c. Bounded, non-blocking startup

**What.** `SessionManager.start` currently calls `relaySession.connect()` (line 304), which runs
`attemptConnect()` **synchronously** on the caller's thread (`RelaySession` line 212), which calls
`connectToRelay(...)` (`Libp2pNetworkService` line 97), which calls `.get()` on two futures with
**no timeout of this project's own**. Whether jvm-libp2p enforces its own dial timeout is
unconfirmed. If it does not, a dead relay at boot hangs a daemon that is meant to "start with no
arguments".

- First connect becomes asynchronous: `connect()` returns immediately; the attempt runs on the
  scheduler thread; the existing backoff handles retries.
- `connectToRelay` gets an explicit overall timeout covering the dial *and* the stream creation.
- Per R-2, nothing on the `relay-session` scheduler thread may block without a timeout; a hung
  dial must not stall keepalives or reconnects.

**Done when.** With the relay address pointed at a black-hole (non-routable) address, `start()`
returns in under 1 second; reconnect attempts continue in the background; once a real relay
appears at that address the session connects without a restart.

**Where.** `SessionManager.start`, `RelaySession.connect`/`attemptConnect`,
`Libp2pNetworkService.connectToRelay`.

#### A-4d. Discovery: the lookup seam and publishing our own record

Three defects live here; all three block "connect to the configured relay" from being useful.

1. **The invite code's `d` field is dead.** `ContactService.addContact` computes
   `discoveryAddress` (line 131) only to null-check it; the injected seam
   `DiscoveryLookup.lookup(String targetPeerId)` (line 65) never receives it, so whatever address
   `DaemonMain` closes over is used for every invite. **Change the seam** to
   `lookup(String discoveryAddress, String targetPeerId)`; the configured relay is the fallback
   when the invite carries no `d`.
2. **Nothing in the daemon path publishes this node's own signed record.** Only the demo
   `PublishSignedRecordMain` does (with a 5-minute TTL). A new `DiscoveryPublisher` builds,
   signs, and publishes `{listen addresses, relayMultiaddr = active relay, pre-key material}` to
   the active relay: at startup, when the relay connects, when the relay/addresses/pre-key pool
   change, and on a timer before expiry. Recommended TTL **24 h**, refresh every **6 h** (DR-8):
   a short TTL means a contact added while this node is briefly offline gets `PEER_NOT_FOUND`
   even though the spool could have delivered.
3. **Trap: `PreKeyBundleFactory.create(store)` (line 28) mints a fresh signed pre-key, Kyber key
   and one-time key on every call and never deletes the previous ones.** A publisher that calls it
   on each refresh will grow the pre-key tables without bound and rotate the signed pre-key every
   6 hours by accident. **The publisher must reuse existing pre-key material, never call
   `create` on refresh.** Pre-key creation and rotation belong to the `PreKeyManager` in F-9.

Also: `DiscoveryController` has no `close()` and `connectToDiscovery` dials anew on every call —
the same lifecycle class as F-3b. `DaemonMain` caches one controller per discovery address and
closes it when idle.

**Where.** `ContactService` (lines 65, 131, 138); new `com.p2pchat.daemon.discovery.DiscoveryPublisher`;
`Libp2pNetworkService.connectToDiscovery`.

#### A-4e. Lifecycle and entry point

**What.** `DaemonMain` runs explicit phases:
`LOAD_CONFIG → OPEN_STORAGE → (identity present? else UNINITIALIZED) → START_NETWORK →
CONNECT_RELAY (async) → PUBLISH_RECORD (async) → START_RPC`. Config must load *before* an
identity exists, which is what makes the first-run flow in F-11a possible. M6h completes the
phases A-4 leaves as stubs (RPC server, logging, shutdown).

#### A-4f. Observability for A-4

Relay state (`CONNECTED` / `CONNECTING` / `FAILED_OVER` / `NONE_CONFIGURED`), the active relay, and
the last successful record publication are exposed through `network.status` (this is also what
fixes the hard-coded `relayConnected: false`, F-11e) and logged at INFO.

#### A-4g. Acceptance criteria

1. **(Original)** A daemon started with **no CLI arguments** and a config naming one relay
   connects to it; the relay logs the registration.
2. **Unreachable relay at boot:** `start()` returns in < 1 s; the daemon connects automatically
   once the relay becomes reachable.
3. **Failover:** with two relays listed, killing the active one for longer than
   `failoverAfterSeconds` moves the daemon to the second; a lookup against the second relay
   returns a record whose `relayMultiaddr` is the new one.
4. **Persistence:** after a restart the previously active relay is tried first.
5. **Config errors:** a relay multiaddr without `/p2p/…` fails startup naming the key; a newer
   `configVersion` is refused; an unknown key logs a warning and is ignored; an empty relay list
   starts direct-only.
6. **Invite `d` honoured:** with two discovery servers, `contacts.add` for an invite carrying `d`
   queries `d`, not the default (test with both servers recording lookups).
7. **No pre-key growth:** after 10 forced republishes, the pre-key tables have the same row counts.
8. **Hardware checkpoint (extends Phase 3):** two NAT-bound daemons, **both started with no
   arguments**, exchange messages and receipts in both directions, including while one is offline.

*Criteria 1–7 are the first automated tests in the repository that start real libp2p hosts; they
are also D-5 progress.*

---

## 12. Track F — second-pass findings and fixes

Numbered **F-1 … F-13** in the order they were raised in review, so they cross-reference the
discussion. Each item: **Finding**, **Why it matters**, **Where**, **Action**, **Done when**.
Severity uses the same scale as §8. Sub-items (e.g. F-3a) are separate deliverables so none is
lost inside a larger one.

### F-1. One JDBC connection shared across threads, with non-reentrant transactions 🔴 · 🔬 Reproduced

**Finding.** `SqliteStorageService` and `SqliteSignalProtocolStore` use the *same*
`database.connection()` (`SqliteDatabase` line 107; `SqliteSignalProtocolStore` line 92). The store
is wrapped in `SynchronizedSignalProtocolStore`'s monitor; `SqliteStorageService` has **no lock at
all**. `runInTransaction` (`SqliteStorageService` line 451) flips auto-commit on that shared
connection, and is **not re-entrant** (`resetChunkState` calls it, and any outer transaction would
be committed early by the inner one). Its two callers, `SessionManager` lines 373 (send, any
thread) and 667 (receive, `inboundExecutor`), can overlap.

**Reproduction.** The project's real `SqliteStorageService` was compiled and driven from two
threads (sqlite-jdbc **3.50.3.0**; the project pins 3.53.2.1 — the experiment must be re-run on
the pinned version as part of the fix):

- *Bystander write lost:* a plain `saveContact` that **returned normally** was not durable (0 rows
  on an independent connection) after another thread's transaction rolled back.
- *Auto-commit stuck off:* two overlapping `runInTransaction` calls left auto-commit `false` in
  **159 of 200** barrier-aligned trials; after that, ordinary writes return success but are never
  committed.

Writing the regression tests exposed two further failure modes on the original code:

- *Overlapping transactions also throw at runtime,* not only lose data silently: the root causes
  seen were `SQLException: database in auto-commit mode` and `SQLITE_ERROR … cannot commit - no
  transaction is active`. In `SessionManager` that surfaces as a received message failing to
  persist.
- *An `Error` inside a transaction commits the partial work.* Only `RuntimeException` triggered a
  rollback; on an `Error`, the `finally` restored auto-commit with the transaction still pending,
  and switching a JDBC connection back to auto-commit **commits** it.

**Why it matters.** Silent data loss. Because the Signal store shares the connection, ratchet
state and **one-time-pre-key deletion** can fail to persist — which bears directly on the forward-
secrecy claim that "a consumed OPK is physically gone". Real-world windows are milliseconds wide,
so it is rare; it is also silent, and an `autoCommit=false` stuck state is permanent for the
process lifetime.

**Where.** `SqliteDatabase`; `SqliteStorageService` (every method); `SqliteSignalProtocolStore`;
`SynchronizedSignalProtocolStore`.

**Action.**
1. `SqliteDatabase` owns one `ReentrantLock`. **Every** public method of `SqliteStorageService` and
   `SqliteSignalProtocolStore` takes it (R-1 order). `SynchronizedSignalProtocolStore` stays as the
   middle layer.
2. `runInTransaction` becomes re-entrant: a depth counter; only the outermost call sets auto-commit
   off and commits; an exception anywhere rolls back the whole; auto-commit is restored in
   `finally` unconditionally.
3. No `Connection` escapes `SqliteDatabase`'s lock discipline. `relay-server`'s `SqliteRelaySpool`
   takes a raw connection too; A-3 documents it as a thread-confined single-worker DAO, which may
   already be sufficient — confirm rather than assume.
4. Alternative considered and rejected for now: a single-thread DB executor. libsignal invokes the
   store from inside its own call stack, so funnelling through an executor adds latency and
   deadlock risk for no gain over a lock.

**Done when.** (a) The two audit experiments exist as JUnit tests and pass: 0 lost bystander writes,
auto-commit `true` after 1,000 overlapping transactions, and — as a property test — the durable row
count equals exactly the writes that should have committed across a mixed 1,000-operation workload.
(b) Nested
`runInTransaction`: an inner success does not commit early; an inner exception rolls back the
outer. (c) A storage transaction in progress blocks a concurrent `storeSession` until it commits.
(d) The tests fail on the pre-fix code (R-5), and were run on sqlite-jdbc 3.53.2.1.

**Status (2026-10-03): implemented; verified on pinned versions.**

- *Code.* `SqliteDatabase` owns the lock (`lease()`), the re-entrant `inTransaction` (depth counter,
  rollback-only propagation, `Error` rolls back, auto-commit always restored) and the `Lease` type.
  `SqliteStorageService` (13 connection sites, all via a one-line lease change) and
  `SqliteSignalProtocolStore` (8 sites) take leases; `runInTransaction` delegates to the database.
  Public API is unchanged; `connection()` remains for single-threaded setup code and tests.
- *Tests.* `SqliteConcurrencyTest` (7) and `SqliteDatabaseLeaseTest` (3) in `core-storage`;
  `SqliteStoreSharedLockTest` (2) in `node-daemon`.
- *What was verified.* `./gradlew :core-storage:test :node-daemon:test` executed on the pinned
  sqlite-jdbc 3.53.2.1 and pinned native libsignal: **all 12 tests pass cleanly**. The full project test suite
  now stands at **443 tests run · 439 passed · 4 skipped (Windows POSIX) · 0 failed**.

### F-2. libsignal's `SessionCipher` is documented not thread-safe; nothing serializes whole operations 🔴 · 🔬 Reproduced (2026-10-03) · 📖 Source-confirmed

**Finding.** `SessionCipher`'s own Javadoc (libsignal v0.94.0) states *"This class is not thread-safe."*
`SynchronizedSignalProtocolStore` makes each store *call* atomic, not the load → ratchet → store
sequence. Concurrent callers for the same peer:

- `sendChatMessage` encrypts on the caller's thread (`SessionManager` line 343);
- `handleChatMessagePayload` encrypts a delivery receipt on `inboundExecutor` (line 627);
- `sendFile` and every file chunk encrypt on RPC / outbound threads (lines 460, 771).

Sending a large file while chatting makes overlap likely.

**Reproduced 2026-10-03** by the project owner (`SessionCipherConcurrencyStressTest`, §15): 20
tasks released together on a pool of 8 threads, all calling `LibsignalSecureSessionService.encrypt`
on one established session. The peer could decrypt only some of the frames — **12 of 20 in the
logged run** (8 `DuplicateMessageException`, e.g. *"message with old counter 12 / 10"*; an earlier
variant of the test logged 4 of 20; counts vary run to run). No error was raised on the *sending*
side, so the sender cannot tell. The error shape is the signature of the hypothesised race: two
threads loaded the same session state and derived the same message counter, so the second frame is
rejected as a duplicate.

Two consequences. **Messages are lost.** And each duplicated counter means two different
plaintexts were produced from the same per-message key material (libsignal derives a cipher key,
a MAC key and a 16-byte IV from each message key, and ships an AES-256-CBC primitive; the exact call
site was not located, so same-key-and-IV reuse is *likely*, not confirmed). The practical leak is
limited — equal ciphertext blocks would reveal shared plaintext prefixes — but it is a
cryptographic-hygiene violation on top of the loss. The production re-run (below) sent payloads with
a common header intended to expose this, but did not compare ciphertext bytes; comparing the first
16-byte ciphertext blocks of frames that share a counter would settle it.

**Confirmed on the production composition (2026-10-03, second run).** The first run used
`InMemorySignalProtocolStore`. `ProductionSessionCipherConcurrencyStressTest` (`node-daemon`)
repeated it on `SynchronizedSignalProtocolStore` over `SqliteSignalProtocolStore` — the exact
production composition. The 20 frames carried only **4 distinct counters** (0, 1, 2, 3), shared by
8, 7, 4 and 1 frames: **16 collisions, observed on the sender before any decryption** by reading
`SignalMessage.getCounter()`. Bob then decrypted exactly **4 of 20 — one per distinct counter —**
with 16 `DuplicateMessageException`. That is the hypothesised race, and it shows the per-call
monitor does not make load → ratchet → store atomic. Not yet exercised: `encrypt` interleaved with
`decrypt` (the same mechanism, but untested).

**Where.** `LibsignalSecureSessionService` (`encrypt` line 30, `decrypt` line 38,
`establishSession`).

**Action.**
1. Per-remote lock *inside* `LibsignalSecureSessionService`
   (`ConcurrentHashMap<String, ReentrantLock>` keyed by remote address) around
   `establishSession`, `encrypt` and `decrypt`, so every caller is covered, not just `SessionManager`.
2. Never hold it across network I/O: `encrypt` returns bytes; the send happens outside the lock.
3. Lock order per R-1. Note in the class Javadoc that libsignal's contract is the reason.
4. Document the benign consequence: with two threads encrypting then sending, message *n+1* can
   hit the wire before *n*; libsignal's skipped-key handling tolerates this.
5. Production constructs exactly one `LibsignalSecureSessionService` (`SessionManager` line 276), so
   an instance-level lock map covers every caller. Do not construct a second instance over the same
   store; the class Javadoc should say so.

**Done when.** The stress test is converted into a CI test that **asserts** — all N frames decrypt
and every counter is distinct, over many rounds — and **fails on the pre-fix code** (R-5). As
pasted, both F-2 tests print their findings and assert nothing, so they pass whether or not the bug
is present and are not yet regression tests. Build it on the production composition, as
`ProductionSessionCipherConcurrencyStressTest` already does, and add an `encrypt`-versus-`decrypt`
interleaving variant. After the fix, the existing scenario should yield **20 distinct counters and
20/20 decrypts**. The lock stays regardless (documented library contract).

- *Implementation & Verification (2026-10-11).* `LibsignalSecureSessionService` wraps `establishSession`,
  `encrypt`, and `decrypt` with a `ReentrantLock` keyed by remote address (`ConcurrentHashMap<String, ReentrantLock>`),
  releasing in `finally` and never held across network operations.
  Lock order adheres to Rule R-1 (`peerCryptoLock -> storeMonitor -> dbLease`).
  Both `SessionCipherConcurrencyStressTest` and `ProductionSessionCipherConcurrencyStressTest` were converted to
  regression tests with strict assertions (pre-fix red phase verified: failed with `DuplicateMessageException` and
  duplicate counters). With the per-peer lock applied, both pass cleanly: **20 distinct counters observed, 0 duplicate
  counter collisions, and 20/20 decrypted**.


### F-3. Transport framing is a library accident; streams are never closed; chunk ceiling is untransportable 🔴 (3a, 3b) / 🟠 (3c) · 🔬 Reproduced (3a, 3b)

**F-3a — No length-prefixed framing on any of the three protocols.** 🔬 Reproduced on jvm-libp2p 1.3.4 (pinned) · 📖 Source-confirmed.
`EnvelopeProtocol` (line 72), `RelayProtocol` (line 79) and `DiscoveryProtocol` (lines 80, 161) all
treat each `onMessage(ByteBuf)` as one complete message. jvm-libp2p's mplex slices any write larger
than 1 MiB (`DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH = 1 shl 20`; `MplexHandler` calls
`sliceMaxSize`) — so a larger message arrives as several `onMessage` calls. **Reproduced
2026-10-03 on the pinned 1.3.4:** a 2 MiB `sendEnvelope` arrived as **two** callbacks of exactly
1,048,576 bytes each, all 2,097,152 bytes present. Today's payloads are mostly small, which is why
nothing has hit it. *Not yet tested:* the boundary sizes (1 MiB − 1, 1 MiB, 1 MiB + 1), the relay
and discovery paths, and what the decoders do with a fragment — that each fragment "fails to
decode" is inferred, not observed (the codecs are hand-rolled binary, not protobuf, so the failure
would be a codec or Signal-MAC error). In upstream the limit is a constructor default, so it may
be raisable, but framing removes the dependence on it and F-3a stands either way.

**F-3b — Streams are never closed.** 🔬 Reproduced on jvm-libp2p 1.3.4 · ✍️ Reviewed.
`EnvelopeController` exposes only `send` (line 5) — there is no `close()`; `Libp2pNetworkService
.sendEnvelope` (lines 64, 112) opens a stream per message and never closes it; the responder's
`onClosed`/`onReadClosed` are empty. Nothing in the repository closes an envelope stream on either
side. **Reproduced 2026-10-03:** after 20 sequential `sendEnvelope` calls (receiver delivered
20/20), reflection on the mplex `streamMap` showed **20 open streams on the sender and 20 on the
receiver** — the leak is on *both* ends, so the fix must close at both. Same class as the relay
leak A-2 fixed. `DiscoveryController` has the same shape (not tested).

**F-3c — The chunk-size ceiling cannot be transported.** 📖 Source-confirmed.
`FileChunker.MAX_CHUNK_SIZE_BYTES` is 8 MiB (line 26) and `validateOffer` accepts up to it, but
without F-3a an 8 MiB frame is sliced by mplex.

**Why it matters.** Fragmented delivery of any message over 1 MiB (decode failure inferred, not
yet observed), and a stream accumulating per message sent, on both ends. File transfer is where
both bite first: a 2 GiB transfer at the default chunk size is ~8,192 streams per direction.

**Action.**
1. **F-3a:** `[4-byte big-endian length][payload]` on all three protocols (Netty's
   `LengthFieldPrepender` / `LengthFieldBasedFrameDecoder`, pushed onto the stream pipeline — verify
   against the pinned jvm-libp2p), `MAX_FRAME_BYTES` = 2 MiB, oversize ⇒ reset the stream and log,
   no allocation. Bump the three protocol IDs to `0.2.0`; multistream-select then refuses
   mismatched peers distinctly (ADR-0001 Category 3 at the transport layer). Part of the
   format-break window.
2. **F-3b:** add `close()` to `EnvelopeController` and `DiscoveryController`; the sender closes
   after the write flushes; the responder closes in `onReadClosed`. Revisit one long-lived framed
   stream per peer only if measurement (D-5) shows per-message streams cost too much (F-10a
   reduces how many are opened). §15 confirms both ends leak, so close at both ends, in the same
change as F-3a — the two share the protocol handlers.
3. **F-3c:** `MAX_CHUNK_SIZE_BYTES` 8 MiB → **1 MiB**; default 256 KiB unchanged. `MAX_FRAME_BYTES`
   leaves headroom for GCM tag, Signal and codec overhead.

**Done when.** In-process integration tests with real libp2p hosts (the first of D-5): payloads of
1 B, 64 KiB, 1 MiB − 1, 1 MiB, 1 MiB + 1 and ~2 MiB − overhead each arrive as exactly one frame on
all three protocols; an oversize frame is rejected without OOM; after 1,000 sequential sends, open
stream counts at both ends return to baseline (mirrors A-1's own "flat after 1,000 sends").

### F-4. Receipts trust the peer 🟠 · ✍️ Reviewed

**Finding.** In `SessionManager.handleChatMessage` (line 615):

- `ReadReceiptPayload` (line 622) calls `markMessagesReadUpTo(read.conversationId(), ownPeerId, …)`
  with the **peer-supplied** `conversationId`. Conversation IDs are `direct-<A>-<B>`, derivable
  from public peer IDs, so **any contact can mark your messages to anyone else as READ**.
- `DeliveryReceiptPayload` (line 618) calls `updateDeliveryState(messageId, DELIVERED)` with no
  check that the message exists, that we sent it, or that it was sent *to this sender*.
- State is not monotonic: a late DELIVERED overwrites READ. (`DeliveryState`'s enum order puts
  `FAILED` last, so ordinal comparison is not a usable rank.)
- A READ receipt emits no event, so a connected UI never learns of it.

**Why it matters.** R-3 violation; integrity of read/delivery state; also a prerequisite for
trustworthy outbound read receipts (inherited M6h backlog).

**Action.**
1. Read receipt: derive the conversation locally from the authenticated sender; ignore the
   payload's `conversationId` (log and drop on mismatch). Emit `onDeliveryStateChanged(…, READ)`.
2. Delivery receipt: apply only if the message exists, `senderPeerId == ownPeerId`, and its
   conversation equals the one derived for this sender; otherwise log and drop.
3. Monotonic updates with an explicit rank `SENDING < FAILED < SENT < DELIVERED < READ`, enforced
   in SQL (`WHERE rank(delivery_state) < rank(?)`) so it cannot be bypassed by a caller. Retry
   (F-8b) uses a separate, explicit `resetForRetry` path.
4. Extract the triplicated `deriveDirectConversationId` into one helper (E-3's own example).

**Done when.** Tests: a contact sends a crafted read receipt naming another conversation — no
state changes; a delivery receipt for someone else's message is ignored; DELIVERED never
overwrites READ; READ produces an event.

### F-5. A relayed first-contact message is not bound to a peer ID 🟠 · ✍️ Reviewed

**Finding.** `handleInboundEnvelope` (line 509) takes `sender` from the transport. For direct
streams that is the Noise-authenticated peer. For relay-delivered frames it is `frame.peerId()` as
**attested by the relay** (`RelayRegistry` correctly stamps the authenticated stream identity —
but a *malicious* relay can stamp anything). On a first-contact PreKey message,
`SqliteSignalProtocolStore.saveIdentity` then pins whatever Signal identity key arrived (TOFU).
The initiator side is well protected (invite → peer ID → Ed25519-signed record → Signal key);
the **inbound-initiated** side has no equivalent binding.

**Why it matters.** The threat model (C-6) names the relay as outside the trust boundary. A
malicious relay could impersonate a peer to a node that has never spoken to them, and — because
`isTrustedIdentity` then rejects the real peer's different key — lock the real peer out.

**Action.** A signed **identity binding** in the handshake (format-break window):

- `HandshakeInit` / `HandshakeResponse` carry an Ed25519 signature, by the sender's libp2p key,
  over `"p2p-chat/identity-binding/v1" ‖ senderPeerId ‖ recipientPeerId ‖ senderSignalIdentityKey`.
- Ed25519 libp2p peer IDs embed the public key, so the receiver verifies **offline** (no lookup).
  `core-discovery` already derives peer ID from key; this adds the inverse.
- The receiver compares the signed key with the identity stored for that address. **Mismatch** ⇒
  delete the session and the **provisional** identity row (DR-4), emit an event, and discard the
  messages.
- **A missing binding is not an immediate drop.** The binding sits inside the encrypted session, so
  the receiver must decrypt first (which creates the provisional session), and the first chat message
  can arrive *before* the handshake — they are separate sends, and on the direct path separate
  streams. Messages from a provisional identity are therefore **quarantined in memory** (≤ 60 s,
  ≤ 32 messages, never persisted or shown) until a binding verifies. On timeout the session and the
  provisional identity are deleted and the quarantined messages discarded.
- The sender **attaches the binding to every outbound message** until it receives the peer's first
  reply (≈ 64 bytes each; a binding field in the chat payload as well as the handshake), so no
  ordering of arrivals can leave a legitimate first contact unbound.
- Identity rows created from an unbound first contact are `provisional` until a binding verifies
  (migration `V005`), so a MITM cannot permanently squat a peer's identity.
- Bumps the handshake codec version; advertise capability `identity-binding`.

This does **not** replace safety-number verification (pre-release item): binding proves *this
Signal key belongs to this peer ID*, not that the peer ID is the person the user intended.
`Contact.verified` keeps its separate meaning.

**Done when.** Tests: a forged first-contact PreKey message attributed to a victim peer ID by a
simulated hostile relay is rejected and leaves no pinned identity; a legitimate first contact
verifies; the real peer can connect after a rejected forgery; a chat message that arrives *before*
its handshake is quarantined and released once the binding verifies; a quarantined message from a
forger is discarded at the timeout and never persisted.

### F-6. Peer-supplied `senderAddress` is dialed blindly 🟡 · ✍️ Reviewed

**Finding.** The daemon dials an address taken from inside a peer's message in three places:
`handleHandshakeMessage` (the handshake reply to `init.senderAddress()`, line 605),
`handleChatMessagePayload` (the delivery receipt to `chat.senderAddress()`, line 691), and
`DefaultFileTransferHandler` (the chunk request to `transfer.senderAddress`, line 283, populated
from `offer.senderAddress()` at line 197). A contact can make the daemon open outbound connections
to any host and port.

**Why it matters.** R-3 violation: port scanning / reflection from the victim's IP, and IP
disclosure to arbitrary hosts, from a single message.

**Action.** A `DialPolicy` applied before any peer-supplied dial (DR-5, as amended):

- require the `/p2p/<id>` component to equal the authenticated sender. This proves *who answers*,
  not that no connection is attempted — the TCP connect to `127.0.0.1:22` happens before Noise
  fails — so the address class must still be filtered;
- always block loopback, link-local, multicast and unspecified;
- allow RFC 1918 / ULA **only on a subnet one of this node's own interfaces is attached to** (the
  same rule as F-10b, implemented once): LAN chat keeps working; probing of unattached corporate
  ranges does not. Apply the same rule to the CGNAT range 100.64.0.0/10 (Tailscale users);
- `/dns*` addresses are resolved first and the *result* is filtered, or peer-supplied DNS addresses
  are refused (otherwise `/dns4/localhost` bypasses the filter);
- per-peer dial rate limit; prefer addresses from the verified discovery record (`peer_routes`) over
  message-embedded ones.

A blocked address falls back to relay — it never errors the message.

**Done when.** Table-driven tests of the policy; a handshake carrying `/ip4/127.0.0.1/tcp/22/…` or
a mismatching `/p2p/`, a `/dns4/localhost/…` address, or a private address on an unattached subnet
causes **zero** outbound dials and a logged drop, with the relay path still used.

### F-7. Relay and receiver resource bounds are incomplete 🟠 · ✍️ Reviewed

A-3 states its own aim: the per-peer quota is *"mandatory, or the relay is a free unbounded disk
for anyone."* The quota is per **target** ID, and anyone can mint target IDs. This item is
**mitigation, not elimination** — an open relay with free identities is a Sybil problem; the goal is
bounded damage, with an invite-only or allow-listed relay as the escalation.

**F-7a — Spool and live path** (= A-3d). Add a **global** cap (total bytes and message count,
configurable) and a **per-sender** quota (the sender *is* authenticated); enforce `MAX_FRAME_BYTES`
on the **live-forward** path too (today only the spool path has a 64 KiB ceiling —
`SqliteRelaySpool` line 52 — live frames are unbounded). Log and count every rejection.
*Where:* `RelayRegistry.onFrame`, `SqliteRelaySpool`.

**F-7b — `DiscoveryRegistry`.** Today an unbounded `ConcurrentHashMap` (line 36) of
`publisher → payload`, no size limit, no signature check, no eviction (expired records are only
*withheld* on lookup). Verify with `DiscoveryRecordCodec.verifyAndDecode(payload, publisher, now)`
on publish (hygiene, not trust — the relay stays untrusted), reject oversize, cap the record count,
evict expired records on a schedule, rate-limit per publisher.

**F-7c — Registration.** Cap concurrent registered peers; log registration churn; per-IP limits if
the library exposes the remote address.

**F-7d — Receiver, pending file offers.** `onFileOffer` (line 166) persists a row and a map entry
per offer *before* the user accepts. Cap pending offers per sender and globally; expire
unaccepted offers (match the 24 h outgoing TTL) and mark them `CANCELLED`; drop beyond the cap with
a log. (An explicit reject path arrives with `files.cancel` in M7.)

**Done when.** Tests: minting 10,000 fake target IDs stops at the global cap; a single sender is
cut off at its quota; oversize live frames are dropped; a forged or unsigned discovery payload is
rejected; registry memory is bounded after 100k publishes; offers beyond the per-sender cap are
ignored and expired offers leave no map entry.

### F-8. No outbox, no retry, and the spool deletes before it is acknowledged 🟠 · ✍️ Reviewed

The plan promised *"at-least-once"* (A-3). What exists is best-effort with two holes.

**F-8a — Persist before send; never swallow a failure.** `sendChatMessage` encrypts first (which
irreversibly advances the ratchet), saves afterward, and its `catch (Exception)` (line 390) returns
`UNREACHABLE` with **no log, no stored message, and no event** — a failed `establishSession` (e.g.
an untrusted identity) is indistinguishable from an unreachable peer. `sendFile` (line 486) and
`encryptAndSendFileTransferMessage` (line 777) do the same.
*Action:* order becomes: allocate ID + HLC → one transaction saving the conversation and the
message as `SENDING` with its plaintext → establish session / encrypt / send → update state.
Failures set `FAILED` with a typed reason (`NO_SESSION_NO_BUNDLE`, `UNTRUSTED_IDENTITY`,
`ENCRYPT_FAILED`, `STORAGE`, `UNREACHABLE`, `RELAY_MISMATCH`), are logged with peer and message ID,
and push `event.message.deliveryStateChanged`. This also delivers the inherited M6h item
*"locally-detected send failure, pushed live."*

**F-8b — Outbox and retry.** A worker retries messages in `SENDING`, retryable `FAILED`, or `SENT`
with no receipt **and no relay `STORED` ack** after a threshold. Backoff 5 s → 30 s → 2 min → 10 min (cap); maximum age **7
days** (= the spool TTL); also triggered on relay reconnect. State in migration `V005`
(`outbox(message_id, attempts, next_attempt_at, last_error)`); target addresses are resolved from
the routing table at retry time, not stored. `messages.retry {messageId}` RPC for manual retry.
**Each retry re-encrypts** (DR-3): resending identical ciphertext that was already processed makes
libsignal reject it as a duplicate, while a re-encrypted message is deduplicated by `messageId`
after decryption (`storage.hasMessage`), which already re-acks. Receivers already send a receipt
for duplicates, so lost receipts heal themselves.

**Retries must not multiply spooled copies (DR-10).** Because every retry is a fresh frame,
retrying `SENT`-without-receipt on the 10-minute cap would put roughly **1,000 copies per message
per week** (144/day × 7) into an offline recipient's spool. They would consume that recipient's
per-target quota — starving every *other* sender to them — and advance the ratchet by the same
amount (libsignal stores only 2,000 skipped keys, `MAX_MESSAGE_KEYS`, and tolerates 25,000 forward
jumps, `MAX_FORWARD_JUMPS`; checked at v0.94.0). The relay never acknowledges a `FORWARD` to the
sender, so the sender cannot tell *relay holds it* from *lost*. *Action:* a relay→sender `STORED`
ack (part of F-8c's relay protocol change). `SENT` splits into **relay-stored** (the outbox waits
for the receipt or the 7-day TTL and does not re-send) and **unconfirmed** (retried with backoff). A
hard cap on `SENT` re-sends (default 3, spaced ≥ 1 h) applies even without the ack.

**F-8c — Delete the spool entry only after acknowledgement** (= A-3b; format-break window).
`RelayRegistry.drainSpooledMessages` calls `spool.delete` (line 72) immediately after
`controller.send` — before the bytes are known to have reached the peer, so a connection dying
right after `send` loses the message. *Action:* relay `DELIVER` carries the spool ID (0 for live
frames); a new relay `ACK` frame from the client, sent after `handleInboundEnvelope` reaches a
**terminal** outcome (processed, definitive duplicate, or definitively invalid — never on a
transient failure such as a storage error or shutdown); the relay deletes on ACK and redelivers
unacknowledged entries on the next registration, with a redelivery cap (default 20) so a poison
frame cannot loop. The same protocol change adds a relay→sender `STORED` frame (DR-10), sent once
a `FORWARD` is durably spooled or handed to a live recipient. Together with F-8b this makes delivery genuinely at-least-once in two layers:
the spool covers *recipient offline*, the outbox covers *loss in transit*.

**F-8d — FIFO per target during a drain** (= A-3c). `onConnected` registers the controller
(`RelayRegistry` line 50) and then drains asynchronously (line 62); a live frame arriving
meanwhile is delivered ahead of the backlog. *Effect, analysed:* arrival order only — libsignal
tolerates reordered ratchet messages inside its skip window, new conversations send PreKey-type
messages until the first reply (so a later message cannot be a bare ratchet message the recipient
lacks a session for), and display order uses the HLC. It is therefore **not** a loss risk by itself,
and is lower priority than F-8c. *Action:* per-target `DRAINING` state that queues live frames
behind the drain.

**Done when.** (a) A forced exception in `establishSession` produces a `FAILED` row with the right
reason, a log line and an event. (b) Killing the sender mid-send leaves a `SENDING` row that is
retried after restart. (c) Dropping the connection immediately after the relay's `send` leaves the
spool row intact and redelivers it; redelivery of an already-processed frame is a no-op. (d) A
retried message is shown once. (e) A frame that fails decoding is acknowledged and not redelivered
forever. (f) Retrying to an offline recipient across a simulated 7 days adds at most the capped number
of spooled copies, and a `STORED` ack stops further re-sends.

### F-9. Pre-key lifecycle: one one-time key per bundle, no rotation, stale cache 🔴 Functional · ✍️ Reviewed

**Finding.** `PreKeyBundleFactory.create` (line 28) produces a bundle with **one** one-time
pre-key and one Kyber key, all stored; the signed pre-key is regenerated on each call and none is
ever removed. The README already records the single-OPK limit; the second-pass reading shows the
failure mode is wider than "refresh cadence":

- the **second** new contact to use a published record references an OPK the first contact
  consumed, and its first message is rejected (`InvalidKeyId`);
- a bundle cached in `peer_routes` at `contacts.add` time can be days old by the first send, with
  the OPK since consumed;
- signed and Kyber pre-keys accumulate (and A-4d's trap makes this worse if unmanaged).

**Action.** A `PreKeyManager` in the daemon, plus a new record body (format-break window; DR-1):

1. **Pool.** The record carries identity key, signed pre-key + signature, a Kyber **last-resort**
   key + signature, and **K one-time EC pre-keys** (default K = 20; ≈ 0.75 KB, far under the
   16 KiB `MAX_BUNDLE_LENGTH`). The *sender* picks one at random and assembles the libsignal
   `PreKeyBundle`. The Kyber last-resort key guarantees a PQ-capable bundle even with an empty pool.
2. **Replenish.** Below a low-water mark (default 5) generate a batch in one transaction, store it,
   and republish the record (A-4d).
3. **Rotate.** New signed pre-key + Kyber last-resort every 7 days; keep the previous pair 30 days
   for in-flight sessions, then `removeSignedPreKey` / delete.
4. **First-send refresh.** Before `establishSession` from a cached bundle older than a threshold
   (default 10 min), re-look-up through the A-4d seam; fall back to the cache if the lookup fails.
5. **Freshness (`sequence`).** `DiscoveryRecord` carries only `expiresAt`, so a relay can serve any
   older valid record — stale one-time-key list, stale relay address — until it expires, and the
   24 h TTL (DR-8) widens that window. The new record body adds a **signed, monotonic `sequence`**
   (and `issuedAt`). A client caches the highest `sequence` seen per peer and **rejects** a lower
   one. This cannot protect a client's *first* lookup of a peer (nothing cached yet); it protects
   every later one.
6. **Accepted residual risks,** written down. (a) With pool size N and *k* simultaneous first
   contacts the collision chance is about k²/2N — negligible for a human-scale messenger; the
   failure mode is a rejected first message, and automatic repair needs the session-recovery
   protocol listed in §16. (b) **Strangers can drain the pool:** anyone holding the record can send
   PreKey messages that consume one-time keys (no block list yet — §16). The Kyber last-resort key
   and signed pre-key keep sessions working with an empty pool, at the weaker rotation-window
   forward secrecy. Replenishment and republish are rate-limited (at most one batch per 10 minutes)
   so a stranger cannot force unbounded key generation.

**Where.** `PreKeyBundleFactory`; `PreKeyBundleCodec`; `DiscoveryRecord.preKeyBundle`;
`SessionManager.sendChatMessage` (`bundleIfNoSessionYet`); `JsonRpcRouter.decodeBundleIfPresent`;
`PeerRoute.preKeyBundle`.

**Done when.** Five distinct senders fetch the same record and **all** establish sessions; the pool
refills after consumption and the record is republished; signed pre-keys rotate on schedule with the
previous pair retained then pruned; 10 republishes add no pre-key rows (A-4g #7); a stale cached
bundle is refreshed before first send; a relay replaying an older record (lower `sequence`) is
rejected by a client that already holds a newer one.

### F-10. Direct-first with no memory; LAN addresses published; relay selection undefined 🟠 · ✍️ Reviewed

**F-10a — Per-peer path memory.** `ConnectionStrategy.send` (line 52) tries the direct address on
**every** send with a 5 s timeout and remembers nothing. Published addresses are LAN IPs
(`DialableAddressResolver`), so across the internet each message — and each file chunk — stalls
5 s before falling back. *Action:* an in-memory per-peer path tracker; after a direct failure,
skip direct until a backoff expires (30 s → 2 min → 10 min cap), then try once with a short
timeout (default 2 s); success resets it. Record the outcome per peer (`DIRECT` / `RELAYED` /
`UNREACHABLE`) — this is the instrumentation Phase 6 says to build from the start — and surface it
through `network.connectedPeers`.

**F-10b — Address scope.** Skip the direct attempt entirely when the target address is RFC 1918 /
ULA / link-local and none of this node's interfaces share its subnet. Interim until Phase 6
(observed-address, UPnP) produces externally reachable addresses. This is the same attached-subnet
rule as F-6's `DialPolicy` — implement it once and use it in both places.

**F-10c — Relay mismatch** (DR-2). With a `RelaySession` present, `ConnectionStrategy` sends via
the node's own relay (line 73) and ignores the contact's `relayMultiaddr`, so contacts on
different relays cannot reach each other. v1 keeps a single shared relay, **detects** the mismatch
(contact's relay peer ID ≠ active relay's) and returns a distinct `RELAY_MISMATCH` reason instead of
silently failing. The check also runs at **`contacts.add`** (the freshly looked-up record's relay
against ours), so the user is told when adding the contact, not only at first send;
`ContactAddResult` gains a non-fatal `RELAY_MISMATCH` warning. On-demand connection to a foreign
relay, or federation, is deferred.

**Done when.** (a) With an unreachable direct address, the second send to the same peer completes
without the 5 s stall; the path outcome is visible in `network.connectedPeers`. (b) A target on a
non-shared private subnet triggers no direct dial. (c) A contact on a different relay yields
`RELAY_MISMATCH` in the send result and event. (d) `contacts.add` for a contact on a different
relay succeeds and returns the warning.

### F-11. API and lifecycle defects that will bite M7 🟠 · ✍️ Reviewed

**F-11a — First-run lifecycle.** `identity.create` (`JsonRpcRouter` line 273) creates the identity
and then calls `sessionManager.localPeerId()`, which throws unless `SessionManager.start` has run —
but `start` needs the identity's key seed. The identity is created, the response fails with
`INTERNAL_ERROR`. *Action:* an explicit daemon state `UNINITIALIZED → READY`; in `UNINITIALIZED`
only `identity.create`, `identity.get` and `network.status` are served and everything else returns
a new `NOT_INITIALIZED` error; `identity.create` derives the peer ID from the new key (no
`SessionManager` needed) and signals `DaemonMain` to start the network. `identity.get` stops
depending on a started `SessionManager`.

**F-11b — Conversations are not first-class.** `contacts.add` (line 302) creates no conversation,
so a client must reconstruct the `direct-<A>-<B>` string (sorted concatenation) to send the first
message, and `resolveOtherPartyOfDirectConversation` (line 455) parses that string. *Action:*
`contacts.add` creates the DIRECT conversation (idempotent) and returns `conversationId`; the router
resolves the peer from storage (a DIRECT conversation's `name` is the other party's peer ID —
`SessionManager` line 374) instead of parsing; the ID format stops being API.

**F-11c — History cannot fetch the latest page.** `queryMessages` (`SqliteStorageService` line 57)
is `ORDER BY hlc ASC LIMIT n` with a forward cursor — the first call returns the *oldest* *n*
messages. *Action:* `messages.history { conversationId, before?, limit }` returns the most recent
page when `before` is absent, otherwise the page older than `before`; ordered ascending for
display, with `nextBefore`. The existing `idx_messages_conv_time` index supports it. **Update
`architecture-spec.md` §7 (line 396)**, which currently specifies `cursor`.

**F-11d — Origin allow-list defaults to empty.** `DaemonWebSocketServer` line 91 uses `Set.of()`, so
any client that sends an `Origin` header (an Electron renderer on `file://` or a custom scheme) is
rejected with 403. *Action:* `rpc.allowedOrigins` in config (A-4a); decide for M7 whether the
Electron main process / preload connects (Node's `ws` sends no `Origin`) or the renderer does with a
configured origin. The default stays deny-all. The token travels as a query parameter: it must never
be logged (D-6's Logback config must scrub query strings).

**F-11e — `network.status` lies about the relay.** `JsonRpcRouter.buildNetworkStatusJson`
(line 509) hard-codes `relayConnected = false`. *Action:* `SessionManager.isRelayConnected()` from
`RelaySession.isConnected()` (A-4f); the existing `onConnectivityChanged` hook already emits
`event.network.statusChanged`. `network.connectedPeers` currently means "has a Signal session";
rename or add the F-10a path outcome so "connected" is honest.

**F-11f — Send and accept error semantics.** `messages.send` (line 336) returns `{messageId}` as
success even when the send failed; with F-8 the truthful response is `{messageId, state}` plus the
failure events. `sendFile` registers the outgoing transfer (`DefaultFileTransferHandler`) *before*
checking a session exists, leaking an entry until the 24 h TTL when `encrypt` then throws, and
returns `PEER_UNREACHABLE` for what is really "no session"; it also takes **no pre-key bundle**, so
sending a file to a brand-new contact cannot establish a session the way `sendChatMessage` can.
*Action:* check the session first (establish from the bundle if absent, as chat does); register the
transfer only after the offer is encrypted; return a distinct `NO_SESSION` error.

**Done when (F-11 as a group).** A fresh data directory goes `identity.create` → `READY` →
`contacts.add` → `messages.send` using only returned IDs; history returns the newest page and pages
backward; an allowed-origin client connects and a disallowed one does not; `network.status` shows
`relayConnected` truthfully and updates live; `files.send` to a no-session contact either
establishes a session or returns `NO_SESSION` and leaks no transfer entry.

### F-12. RPC handlers run on Netty I/O threads 🟠 · ✍️ Reviewed

**Finding.** `DaemonWebSocketServer` adds the frame handler directly to the worker event loop
(line 128), so `JsonRpcRouter.onMessage` (line 139) runs on it. `messages.send` performs PQXDH,
encryption and SQLite writes there; `files.send` hashes the **whole file** (`sendFile` →
`FileChunker.sha256HexOfFile`) there. Similarly `ContactService.resolveLookupResult` runs storage
writes on a libp2p callback thread.

**Why it matters.** One large `files.send` stalls every WebSocket client on that event loop. This
violates R-2.

**Action.** Register the frame handler with its own `DefaultEventExecutorGroup`
(`pipeline.addLast(rpcExecutorGroup, frameHandler)`) — one executor per channel preserves per-client
ordering (DR-6) — and hop libp2p/relay callbacks onto an owned executor. Hashing and any other
long file work move off the caller to a dedicated pool; the router already returns
`CompletableFuture`, so no API change.

A per-connection executor guarantees **dispatch order, not completion order**: a handler that
finishes asynchronously (`contacts.add` waits on a network lookup) can still complete after a later
request was dispatched, so clients must await a response before depending on its effect. A slow
handler on a connection's executor also blocks *that client's* later requests (head-of-line), so
nothing slow may run there — hashing and file I/O go to the separate pool — and the group stays
small (2–4 threads), since few WebSocket clients exist.

**Done when.** A latency test: a `files.send` of a ≥ 1 GiB file does not delay an unrelated
`network.status` on another connection beyond a stated bound (measured, asserted).

### F-13. Smaller defects 🟡 · ✍️ Reviewed

**F-13a — Zero-byte files cannot be sent.** `FileChunker.chunkCount(0, …)` returns 0 (line 32) but
`validateOffer` (`DefaultFileTransferHandler` line 224) rejects `totalChunks <= 0`; the sender gets a
success and the receiver silently drops the offer. *Action:* support empty files (accept → create the
empty file → verify the empty hash → `COMPLETED`), with a test. (A zero-byte file is a valid file.)

**F-13b — Display-name newline corrupts `identity.meta`.** `JavaIdentityService` writes
`displayName + "\n" + createdAt` (line 53) and reads it back with `split("\n")` (line 71).
*Action:* reject control characters and cap length at `createIdentity`; parse by the **last**
newline so existing files stay readable.

**F-13c — `MigrationRunner` splits scripts on `;`.** (`MigrationRunner` line 122.) A semicolon
inside a SQL comment, a string literal or a trigger body breaks a migration. It works today only
because no existing script has one. *Action:* a small state-machine splitter that respects `--`
comments, `/* */`, quoted strings and `BEGIN … END`; a unit test with each case; a test that every
migration file on the classpath applies to a fresh database.

**F-13d — HLC accepts arbitrarily old timestamps.** `checkDrift` (`HybridLogicalClock` line 191)
bounds only the *future* (5 min), so a contact can backdate messages to the top of a conversation.
*Action:* also reject timestamps older than a configurable maximum (default **30 days**, comfortably
above the 7-day spool TTL and the outbox's 7-day maximum age).

**F-13e — Chunks are encrypted twice and each costs a SQLite write.** File chunks are AES-GCM
encrypted *and* wrapped in a Signal message, so every chunk advances the ratchet and persists the
session blob. *Action (DR-9): measure first.* Add a 100 MB throughput benchmark to D-5, run **over the relay
path with a realistic round-trip time** as well as loopback: chunks are sent strictly one at a time
(the `thenCompose` chain in `onFileChunkRequest`), so throughput is latency-bound and loopback hides
that. Note SQLite is not in WAL mode today (`SqliteDatabase` sets only `foreign_keys`), so each
ratchet write is a rollback-journal commit; any journal or `synchronous` change must keep ratchet
state durable. Proposed acceptance: ≥ 15 MB/s on loopback and a stated floor over the relay. If it
is unacceptable, try **windowed (pipelined) chunk sends** first; only then move bulk data to a
dedicated framed stream protocol
(`/p2p-chat/filedata/0.1.0`) authenticated by the stream's remote peer ID and the offer's file
key, keeping the Signal-wrapped path as the relayed fallback. Do not build it speculatively.

**F-13f — Receiver does not check chunk sender.** `onFileChunk` (line 345) and `onFileChunkRequest`
(line 287) do not verify that `sender` equals the transfer's peer. Authenticated encryption makes
forgery fail, so this is defence in depth only. *Action:* one `if` and a log line in each.

**F-13g — GCM chunks carry no AAD** (format-break window). `ChunkCipher` (lines 39, 53) binds neither
`transferId`, `fileHash` nor `chunkIndex`. Harmless while chunks travel inside a Signal session;
it matters the moment chunks are shared across peers (M8-era swarm) or move to F-13e's bulk stream.
*Action:* AAD = `transferId ‖ fileHash ‖ chunkIndex ‖ chunkSize`, passed through
`ChunkCipher.encrypt`/`decrypt`. Receivers already know all four values, so there is no extra wire
field — but chunks from older builds stop decrypting, hence the window.

**F-13h — `relayConnected`, history ordering, Origin default** are tracked under F-11e, F-11c and
F-11d respectively.

**Done when (F-13).** Each of a–g has a regression test that fails on the pre-fix code (R-5).

---

## 13. Decision register

Choices that need a human before the dependent item starts. Each has a recommended default so
work is not blocked if the default is accepted; record the outcome in the *Decision* column.

| ID | Decision | Options | Recommended default | Needed by | Decision |
|:---|:---|:---|:---|:---|:---|
| DR-1 | Pre-key strategy (F-9) | (A) pool of one-time keys in the record + Kyber last-resort · (B) OPK-less bundle | **A**, K = 20, low-water 5, last-resort always present | F-9, A-4d | ☑ **Accepted 2026-10-03.** Amended: record the pool-drain trade-off (strangers can burn one-time keys; the last-resort key is the mitigation, and it shares the signed pre-key's exposure window). |
| DR-2 | Relay topology v1 (F-10c, A-4b) | single shared relay · on-demand foreign-relay connection · federation | **Single shared relay**, explicit `RELAY_MISMATCH`; revisit in the NAT programme | A-4b | ☑ **Accepted 2026-10-03.** Amended: also detect at `contacts.add` and warn then, not only at first send. |
| DR-3 | Outbox retry semantics (F-8b) | re-encrypt each retry · resend stored ciphertext | **Re-encrypt**; dedupe by `messageId`; max age 7 d | F-8b | ☑ **Accepted 2026-10-03.** Amended: retries must not multiply spooled copies — see DR-10 and F-8b. |
| DR-4 | Identity-binding enforcement (F-5) | mandatory · optional with warning | **Mandatory** in the new format; failure deletes the session and the provisional identity row | F-5 | ☑ **Accepted 2026-10-03.** Amended: the binding is inside the encrypted session, so **quarantine** (≤ 60 s, ≤ 32 messages, memory only) instead of dropping immediately; attach the binding to every outbound message until the peer's first reply (F-5). |
| DR-5 | Peer-address dial policy (F-6) | block all private · allow RFC 1918 with `/p2p/` match | **Allow RFC 1918 with match**; block loopback/link-local/multicast/unspecified; rate-limit | F-6 | ☑ **Accepted 2026-10-03.** Amended: private ranges only on subnets one of our own interfaces is attached to (same rule as F-10b); `/dns*` filtered after resolution or refused; CGNAT under the same subnet rule. The `/p2p/` match proves who answers, not that no dial happens. |
| DR-6 | RPC threading model (F-12) | own event-executor group · shared worker loop | **`DefaultEventExecutorGroup`**, one executor per channel; blocking file work on a separate pool | F-12 | ☑ **Accepted 2026-10-03.** Amended: a per-connection executor guarantees dispatch order, not completion order; clients must await responses; nothing slow may run on a connection's executor (head-of-line blocking). |
| DR-7 | Extension block in bumped codecs (§10.5) | add trailing length-prefixed extensions · leave fixed | **Add** (≤ 1 KiB, ignored if unknown) | window | ☑ **Accepted 2026-10-03.** Amended: extensions lie **inside the signed bytes** wherever a codec is signed; a type ≥ 0x8000 is *critical* (reject if unknown). |
| DR-8 | Daemon state store and record TTL (A-4b, A-4d) | SQLite `daemon_state` vs `state.json`; TTL 1 h vs 24 h | **SQLite `V005`**; **TTL 24 h, refresh 6 h** | A-4b, A-4d | ☑ **Accepted 2026-10-03.** Amended: add a signed monotonic `sequence` to the record; clients reject rollback (F-9). |
| DR-9 | Bulk file protocol (F-13e) | build now · measure first | **Measure first**; build only if the benchmark fails | M7 | ☑ **Accepted 2026-10-03.** Amended: SQLite is not in WAL mode today and chunks are sent strictly one at a time, so throughput is latency-bound — benchmark over the relay with a realistic round-trip time and try windowed sends before any new protocol (F-13e). |
| DR-10 | Relay acknowledges `FORWARD` to the sender (F-8b, F-8c) | no ack — the sender cannot tell *relay holds it* from *lost* · relay→sender `STORED` ack | **`STORED` ack** in relay protocol `0.2.0`; the outbox stops re-sending a relay-stored message until the receipt or the 7-day TTL; hard cap on `SENT` re-sends even without the ack | F-8b, F-8c | ☑ **Accepted 2026-10-03.** Added after review of DR-3. |

All nine decisions were accepted on 2026-10-03 with the amendments shown in the last column;
**DR-10** was added the same day after reviewing DR-3 (see F-8b).

---

## 14. Revised sequence and dependencies

This amends Phase 3 of §7 (and adds work to Phases 4–5). It does not reorder any phase that is
already complete.

### 14.1 Dependency table

| Item | Depends on | Wire / schema impact | Window |
|:---|:---|:---|:---|
| F-1 DB lock, re-entrant tx | — | none | no |
| F-2 per-peer crypto lock | R-1 (F-1) | none | no |
| F-3a framing + protocol bump | — | **wire** | **yes** |
| F-3b stream close, both ends | F-3a (same handlers) | none | ships with F-3a |
| F-3c chunk cap | — | validation only | no |
| F-4 receipt authorization | — | SQL rank | no |
| F-5 identity binding + quarantine | F-3a, DR-4 | **wire** (handshake **and** chat payload) + `V005` | **yes** |
| F-6 dial policy | DR-5 | none | no |
| F-7a–d resource bounds | F-3a (frame cap) | none | no |
| F-8a persist-before-send | F-1, F-2 | `V005` | no |
| F-8b outbox worker | F-8a, F-2, DR-3, DR-10 (STORED ack from F-8c) | `V005` | no |
| F-8c ack-based spool + `STORED` ack (DR-10) | F-3a | **wire** | **yes** |
| F-8d FIFO drain | — | none | no |
| F-9 pre-key pool + record `sequence` | DR-1 | **wire** (record) + `V005` | **yes** |
| F-10a–c path/selection | A-4b (c) | none | no |
| F-11a–f API/lifecycle | A-4e | spec §7 text | no |
| F-12 RPC off I/O thread | R-2 | none | no |
| F-13a–f small defects | — | none | no |
| F-13g GCM AAD | — | **wire** | **yes** |
| A-4a config | — | config schema v1 | **yes** |
| A-4b relay manager | F-1, DR-2, DR-8 | `V005` | no |
| A-4c async startup | — | none | no |
| A-4d publisher + seam | F-1, F-9 (record body), DR-1, DR-8 | uses window format | no |
| A-4e/f/g entry point, status, acceptance | A-4a–d | — | — |

### 14.2 Order

**3.0 — Independent fixes (start now; in parallel with the owner's stress tests, §15).**
**F-1** first (reproduced, no need to wait; **implemented and verified 2026-10-03/2026-10-11** with all 12 storage tests passing), then F-4, F-6, F-13a/b/c/d/f, F-7d, F-3c, F-11e. None
changes a wire format; all have no unmet dependency. (F-11e here means only the `relayConnected`
fix; the rest of F-11 waits for A-4e.)

**3.1 — Unblock A-4.** **A-4a** (config) and **A-4c** (bounded, async startup) need only F-1. They
alone make A-4's original *Done when* demonstrable.

**3.2 — Concurrency.** **Results are in (§15, 2026-10-03):** F-2 is confirmed, implemented, and verified (**2026-10-11**; 20/20 decrypts, 0 duplicate counter collisions across both in-memory and production compositions), moving ahead of F-8 and F-12. F-3b is confirmed on both ends and ships with F-3a in
3.3 (same handlers) rather than waiting here. **F-12** (executors) follows R-2.

**3.3 — The format-break window.** One coordinated change, one ADR-0002: **F-3a, F-5, F-8c (with the `STORED`
ack), F-9 (with `sequence`), F-13g**, DR-7's extension block, A-4a's `configVersion`. Close the window when the first build
leaves the owner's machines.

**3.4 — Finish Track A.** **A-4b, A-4d, A-4e/f/g**, **F-10a–c**, **F-7a–c** (= A-3d). Run the
hardware checkpoint in A-4g #8. Apply the Track A closure criteria (§11).

**3.5 — Reliability (inherited M6h backlog, now specified).** **F-8a, F-8b, F-8d**, with the
send-failure events.

**Phase 4 additions.** Every item's regression test (R-5); the in-process libp2p integration tests
from F-3a and A-4g become the first of D-5's cross-process tests; add the F-13e throughput
benchmark and a 30-minute soak test (stream and connection counts flat).

**Phase 5 (M6h) additions.** F-11a–f and F-12 land with `DaemonMain`; Logback config scrubs the
token (F-11d).

**Update C-6 (threat model)** with: the malicious-relay impersonation window and its F-5 closure;
F-7's bounded-not-eliminated Sybil posture; the unauthenticated discovery lookup (§16).

---

## 15. Owner stress tests (F-2 and F-3)

The project owner wrote and ran these (they need native libsignal and jvm-libp2p, which the audit
environment could not load); results were recorded **2026-10-03**. Results are recorded here; **they decide the priority and scope of F-2
and F-3b, not whether the cheap fixes happen** (the F-2 lock follows a documented library
contract either way).

**What each test must show**

| Test | Setup | Evidence to record |
|:---|:---|:---|
| **F-2 ratchet race** | Real libsignal, one established session pair. N threads concurrently `encrypt` on the sender while M threads `decrypt` replies, repeated for ≥ 10,000 operations, with and without a per-peer lock. | Decrypt failures; duplicate or skipped message counters; sessions where the stored ratchet state disagrees between peers; whether any plaintext is lost. |
| **F-3a fragmentation** | Real libp2p hosts. Send payloads of 512 KiB, 1 MiB − 1, 1 MiB, 1 MiB + 1, 3 MiB, 8 MiB through `sendEnvelope`, and the same through the relay path. | How many `onMessage` calls the receiver sees per send; whether the decoded payload is intact; the jvm-libp2p version tested. |
| **F-3b stream lifecycle** | 1,000 sequential `sendEnvelope` calls to one peer (and 1,000 relay sends). | Open stream count at both ends before and after (via library introspection or `ss -tn`); heap before and after a forced GC; whether the numbers grow linearly. |

**Results log** (project owner's own terminology: *Finding 1* = F-2, *2A* = F-3a, *2B* = F-3b)

| Test | Date | Versions | Result | Plan impact |
|:---|:---|:---|:---|:---|
| F-2 ratchet race (`SessionCipherConcurrencyStressTest`, `core-crypto`) | 2026-10-03 | libsignal as pinned in `core-crypto` | 🔬 **Reproduced.** 20 concurrent `encrypt` calls on one session: **12/20 decrypted, 8 failed** with `DuplicateMessageException` (raw log); 4/20 in an earlier variant. 0 encrypt-side errors. | Stays 🔴; first item of 3.2; ahead of F-8 and F-12 |
| F-2 on the **production composition** (`ProductionSessionCipherConcurrencyStressTest`, `node-daemon`) | 2026-10-03 | libsignal as pinned; `SynchronizedSignalProtocolStore` over `SqliteSignalProtocolStore` | 🔬 **Reproduced.** 20 concurrent `encrypt` calls: **4 distinct counters for 20 frames** (shared by 8, 7, 4, 1) — 16 collisions seen on the sender; **4/20 decrypted** (one per counter), 16 `DuplicateMessageException`. | Confirms the per-call monitor is insufficient; ordering unchanged |
| F-3a fragmentation (`TransportFramingAndStreamLeakStressTest`, `core-network`) | 2026-10-03 | jvm-libp2p **1.3.4** | 🔬 **Reproduced.** 2 MiB `sendEnvelope` → **2** `onMessage` callbacks × 1,048,576 bytes; 2,097,152/2,097,152 bytes present. | F-3a proceeds as specified; the 1 MiB limit is now confirmed on the pinned version |
| F-3b stream lifecycle (same class) | 2026-10-03 | jvm-libp2p **1.3.4** | 🔬 **Reproduced.** 20 sequential sends (20/20 delivered) → mplex `streamMap` = **20 on the sender and 20 on the receiver**. | F-3b upgraded to 🔴; close at **both** ends; ships with F-3a |

**Notes on the tests as run.** Recorded so the evidence is read at its true strength.

1. **Two variants of the F-2 test were reported.** The summary quoted
   `testConcurrentEncryptOnSameSessionCausesRatchetDesyncOrDrop` with 4/20 successes and a
   `[Concurrent Stress Test]` output prefix; the pasted source and raw log are
   `concurrentEncryptionOnSameSessionCorruptsRatchetOrFailsDecryption` with 12/20. This document
   records the raw log. The counts are non-deterministic; the *failure*, not the number, is the
   finding.
2. **Store used (resolved).** The first F-2 run used `InMemorySignalProtocolStore` although the
   report described the production store. The production composition was re-run the same day and
   reproduced the race — see the second F-2 row. (That second test also asserts nothing.)
3. **Thread count.** The report says 20 threads; the code runs 20 tasks on a pool of 8.
4. **The F-2 test asserts nothing** (it prints and passes). **The F-3a and F-3b tests assert the
   defect** (`hasSizeGreaterThan(1)`, `isEqualTo(sendCount)`), so they pass today and will fail the
   moment the fix lands.
5. **Scale.** The plan asked for 1,000 sequential sends; 20 were run. Linear growth is established,
   not the 1,000-send figure. No heap measurement was taken.
6. **Brittleness.** The F-3 tests use fixed ports (19310–19321) and read the mplex `streamMap` by
   reflection (a library internal).

**Not covered by these tests:** `encrypt` interleaved with `decrypt`; whether the duplicate-counter
frames really share key *and* IV (ciphertext blocks were not compared); payloads at 1 MiB − 1 / 1 MiB / 1 MiB + 1; the relay and discovery protocols (the
relay uses one persistent stream; discovery dials per call and is the likelier second leak);
what decoders do with a fragment; heap growth.

**Conversion to regression tests (R-5).** Keep the test files; change them as follows when the
fixes land.

| Test | Today | Becomes |
|:---|:---|:---|
| F-2 | prints, asserts nothing | assert all N frames decrypt and every counter is distinct, over many rounds; fails on pre-fix code; build on the production composition (done in the second test); add the `encrypt`/`decrypt` interleaving variant; optionally compare ciphertext blocks to settle key reuse |
| F-3a | asserts fragmentation (`> 1` callback) | assert **exactly one** frame for each of 1 B, 64 KiB, 1 MiB − 1, 1 MiB, 1 MiB + 1, ~2 MiB − overhead, on all three protocols; assert an oversize frame is rejected without OOM |
| F-3b | asserts 20 leaked streams | assert `streamMap` returns to baseline on **both** ends after N sends; raise N toward 1,000; use ephemeral ports if `start` supports port 0; guard the reflection with a clear failure message |

**Outcome against the decision rules**

- **F-2 reproduced** → severity stays 🔴; F-2 moves ahead of F-8 and F-12. *(Applied in §14.2.)*
- **F-3a shows fragmentation on the pinned version** → F-3a proceeds as specified; the 1 MiB figure
  is a confirmed constraint. *(Applied; F-3a text updated.)*
- **F-3b shows linear stream growth** → F-3b becomes 🔴 and ships with the window. *(Applied.)*
  The one-long-lived-stream-per-peer option stays deferred: close-after-send is sufficient to remove
  the leak, and F-10a reduces how many streams are opened.

---

## 16. Noted but not scheduled

Observed during the second pass and recorded so they are not lost. **They are not part of the
agreed work above.** Promote an item by giving it a Track F number.

- **Session recovery and identity-change handling.** Inbound decrypt failures are only written to
  stderr (no event). A peer whose identity key changes (reinstall, lost database) is rejected
  permanently by `isTrustedIdentity`, with no re-trust path short of editing the database. A rejected
  first message after an OPK collision (F-9) has no automatic repair. This needs a session-reset
  protocol and the safety-number-change UX (pre-release item). It is the main thing F-9's residual
  risk and the F-8 reasoning lean on.
- **No block list or message-request gating.** `handleInboundEnvelope` accepts a first-contact
  message from anyone who holds your published record. The spec calls a local block list
  "must be usable and prominent" for v1.
- **Discovery lookups are unauthenticated.** Anyone holding a peer ID can fetch that peer's
  addresses and bundle (presence and IP disclosure); this belongs in the threat model (C-6).
- **`missingChunks` is O(totalChunks) per received chunk,** so O(n²) over a transfer (≈ 8,192 chunks
  at the 2 GiB maximum). Replace with a count/bitmap if profiling shows it matters.
- **The HLC is in memory.** After a restart it relies on the wall clock; persist the last value if
  clock regression becomes a real problem.
