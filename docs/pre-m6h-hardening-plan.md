# Pre-M6H Hardening & Remediation Plan

> **Status:** Proposed — not yet started
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

### A-4. Relay configuration & selection

**What.** The daemon needs to *know* its relay. Today relay addresses arrive as CLI args on demo
Mains. `DaemonMain` needs a config file (`~/.p2p-chat/config.json` or similar) with a bootstrap
relay list, plus persistence of which relay this node is registered with — the invite-code `d`
field and `DiscoveryRecordV2.relayMultiaddr` both already anticipate this.

**Done when.** A daemon starts with no CLI arguments and connects to its configured relay.

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
| 5 | Protocol version byte + capabilities | B-1/B-2 | 🟠 Structural | **Yes** |
| 6 | Persistent relay session + `close()` | A-1 | 🔴 Structural | **Yes** |
| 7 | Relay inbound reception | A-2 | 🔴 Functional | **Yes** |
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
