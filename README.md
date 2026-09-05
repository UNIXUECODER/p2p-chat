# p2p-chat

A decentralized, end-to-end encrypted peer-to-peer chat and file-sharing application. Built entirely in Java (JDK 21), with no central server controlling any user data. The architecture is designed so the UI layer (Electron desktop, Android) is a pure client of a local JSON-RPC 2.0 API — no frontend code ever touches cryptographic keys, libp2p internals, or the SQLite database directly.

For the full design rationale, module contracts, wire protocol, and storage schema, see [`docs/architecture-spec.md`](docs/architecture-spec.md).

---

## Project status

> **Verification vocabulary** (see [`docs/verification-vocabulary.md`](docs/verification-vocabulary.md)
> for the full definitions and the reason this exists): unless noted otherwise, **✅ Verified**
> below means ✅ **Verified (hardware)** — executed on real hardware against real `jvm-libp2p`,
> `libsignal-client`, and SQLite, not a sandbox or compile-only pass. Where a milestone was only
> 🧪 tested or 🔨 compiled at the time it was written, that's called out explicitly in its own
> section further down rather than left implicit in this summary table.

| Milestone | Description | Status |
|:---|:---|:---|
| M0 | Identity generation & local key vault | ✅ Verified |
| M1 | Direct P2P connectivity (libp2p ping over TCP) | ✅ Verified |
| M1.5 | Persistent peer identity binding | ✅ Verified |
| M2a | PQXDH + Double Ratchet in isolation | ✅ Verified |
| M2b | Custom libp2p envelope protocol | ✅ Verified |
| M2c | End-to-end encrypted messages over a real connection | ✅ Verified |
| M3a | Relay mechanism (store-and-forward via relay-server) | ✅ Verified |
| M3b | Direct-first, relay-as-fallback connection strategy | ✅ Verified |
| M3c | Peer discovery (publish/lookup via relay-server) | ✅ Verified |
| M4a | Chunking + per-chunk AES-256-GCM encryption, proven in isolation | ✅ Verified |
| M4b | File-transfer wire payloads, proven in isolation | ✅ Verified |
| M4c | Real, single-peer file transfer over an encrypted connection | ✅ Verified |
| M4d | Chunk-level resume via core-storage | ✅ Verified |
| M4e | `conversations`/`messages` foreign-key fix | ✅ Verified |
| M5 | 1:1 messaging (`core-messaging`): send/receive, delivery/read receipts, HLC ordering, dedup — **M5a, M5b, M5c, M5d all done and verified on real hardware** | ✅ Verified |
| M5e | Pre-M6 cleanup pass: file-transfer deadlock fix, decoder/marker validation, HLC remote-drift guard, M5d dedup confirmed live — see the M5e section below | ✅ Verified |
| M6 | `node-daemon` composition root + local JSON-RPC/WebSocket API (§7), 1:1 scope — **M6a–M6g-4 done** (dispatch, outbound send, JSON model, WebSocket, Signal store + SessionManager, signed discovery, JSON-RPC router); **M6g-5** (pre-M6h hardening pass, 🔨 compiled/hand-traced, not yet hardware-verified) done; **M6h** (`DaemonMain`) remaining, after Tracks A/B from the hardening audit — see [`M6g-gap-analysis-and-plan.md`](docs/M6g-gap-analysis-and-plan.md) and [`pre-m6h-hardening-plan.md`](docs/pre-m6h-hardening-plan.md) | 🔄 In Progress |
| M7 | Electron frontend wired to the API — 1:1 chat + file transfer UI | 🔜 Planned |
| M8 | Group chat: CRDT membership + sender-key group encryption (`core-groups`) | 🔜 Planned |
| M9 | Packaging & Android port | 🔜 Planned |

> **Note on this reordering (see also the M4e section below):** the original plan bundled "group
> chat" and "Electron UI" into single M5/M6 milestones. Two changes from that plan, both decided
> deliberately rather than discovered as a gap:
>
> 1. **`core-messaging` (real 1:1 send/receive, delivery/read receipts, HLC ordering, message
>    dedup) and `core-groups` (CRDT membership + sender-key rotation) were always two separate
>    modules in `docs/architecture-spec.md §3` — the old milestone table just bundled them into
>    one "M5" line. Splitting them into separate milestones (M5 vs. M8) isn't a scope change; it's
>    correcting the milestone table to match a module boundary the spec already had.
> 2. **Group chat is deliberately deferred past the UI milestone**, not skipped. It's the
>    hardest, least-proven remaining piece — no `core-groups` code exists yet, no HLC
>    implementation exists anywhere in this project despite being referenced throughout the spec,
>    and OR-Set CRDT merge semantics aren't something that can be hand-traced with confidence the
>    way M1–M4 were. Getting a working 1:1 product first, then adding groups, means the hardest
>    subsystem gets built with a real UI and real messaging pipeline already in place to build
>    against — and it's additive when it lands: `conversations.type` is already `DIRECT | GROUP`,
>    `conversations.createGroup` is already in the §7 RPC table, and `GROUP_OP` is already a
>    numbered `EnvelopeType` (§6). Sender-key group encryption also rides on top of the pairwise
>    Double Ratchet sessions M2/M5 already establish, rather than replacing them — so the 1:1
>    crypto path is a prerequisite for groups, not a detour from them.
>
> One caution worth stating plainly: M6 (daemon + JSON-RPC API) is bigger than "just wire up the
> UI." Everything built through M4d is a one-shot CLI demo — no long-running process holds
> multiple simultaneous peer sessions, nothing resembling the §7 WebSocket server exists yet, and
> every demo still hand-carries a bundle file or multiaddr between terminals rather than using
> M3c's discovery automatically. M6 is where that plumbing gets built for the first time.

---

## Requirements

- **JDK 21** — [Eclipse Temurin](https://adoptium.net/) is a good distribution if you don't have one installed.
- **Gradle 8.x** — easiest via [SDKMAN](https://sdkman.io/): `sdk install gradle`

Once Gradle is installed, generate the wrapper once so the rest of the team doesn't need Gradle installed locally:

```bash
cd p2p-chat
gradle wrapper --gradle-version 8.10
```

---

## M0 — Identity & local key vault

Generates and persists an Ed25519 keypair to `~/.p2p-chat-data` (relative to the working directory). A peer ID is derived as a SHA-256 hash of the public key (hex-encoded) — a simplified placeholder that is visually distinct from the real libp2p multihash format added in M1.5, but derived from the same underlying key material. On subsequent runs, the same identity is loaded from disk.

Three files are written to the data directory:
- `identity.pub` — DER-encoded public key
- `identity.key` — PKCS#8-encoded private key (⚠ currently plaintext — see §8 of the architecture spec for the planned OS-keychain integration)
- `identity.meta` — display name and creation timestamp

```bash
# First run — creates a new identity
./gradlew :node-daemon:run --args="Alice"

# Subsequent runs — loads the same identity
./gradlew :node-daemon:run
```

### Deliberately not in M0

- **At-rest encryption of the private key** — the key file is plaintext on disk. OS keychain integration (`macOS Keychain` / `Windows DPAPI` / `Linux Secret Service`) is required before this is used with a real identity. Do not use this skeleton beyond local development.
- **A running local API server** — `node-daemon` currently just prints identity info and exits. The WebSocket JSON-RPC server is added once there is application-level state worth exposing.

---

## M1 — Direct P2P connectivity ✅

Two `jvm-libp2p` nodes connecting to each other and exchanging a real ping, proving raw TCP P2P connectivity works before any application protocol is built on top. Uses jvm-libp2p's built-in `ping` protocol directly — one of the production-tested components of that library (TCP transport, Noise encryption, mplex multiplexing, ping/identify are all ✅ in jvm-libp2p's own status table; Kademlia DHT, hole-punching, and Circuit Relay v2 are not, which is why the architecture diverges from go-libp2p's assumptions — see `docs/architecture-spec.md §10`).

**Two terminals on the same machine:**

```bash
# terminal 1
./gradlew :node-daemon:runListener
# prints something like: /ip4/0.0.0.0/tcp/9000/p2p/16Uiu2HA...

# terminal 2 — swap 0.0.0.0 for 127.0.0.1
./gradlew :node-daemon:runPinger -Paddr="/ip4/127.0.0.1/tcp/9000/p2p/16Uiu2HA..."
```

Five ping latencies will print in terminal 2. That is two independent libp2p hosts over a real TCP socket.

**Two machines on the same LAN** — same commands, but use the listener machine's actual LAN IP instead of `127.0.0.1`.

### Deliberately not in M1

- No application encryption yet (`ping` uses libp2p's own Noise transport security, but Signal-style Double Ratchet sessions are M2).
- No custom application protocol — this only proves the transport pipe works.
- No internet-scale discovery — both sides need to already know each other's address.

---

## M1.5 — Persistent peer identity ✅

M1 had a real gap: `HostBuilder` was generating a **brand new random libp2p identity on every run**, completely disconnected from the Ed25519 identity `core-identity` creates and persists in M0. Restarting the listener produced a different peer ID every time.

**Fix:** `IdentityService.rawPrivateKeySeed()` extracts the raw 32-byte Ed25519 seed from the persisted M0 identity (using the JDK's `EdECPrivateKeySpec`) and passes it into `HostBuilder` via its `.builderModifier()` escape hatch, which reaches into the same `IdentityBuilder.factory` hook that `.random()` uses internally. Both `runListener` and `runPinger` now load (or create) the persisted identity first.

**Testing on one machine now requires `-Pdatadir`** — if both processes read the same default `.p2p-chat-data` folder, they load the same keypair and claim to be the same peer, which defeats the test. Use a separate data dir per instance:

```bash
# terminal 1
./gradlew :node-daemon:runListener -Pdatadir=.p2p-chat-data-listener

# terminal 2
./gradlew :node-daemon:runPinger -Pdatadir=.p2p-chat-data-pinger -Paddr="/ip4/127.0.0.1/tcp/9000/p2p/..."
```

**To verify the fix:** stop and restart `runListener` a couple of times. The `/p2p/<peer-id>` suffix it prints must be identical every time.

> **Note on peer ID format:** `core-identity`'s `Identity.peerId()` (the SHA-256 hex placeholder from M0) and the libp2p `Host`'s own peer ID string (proper base58 multihash) are both derived from the **same underlying Ed25519 keypair**, so they agree on the key material — but they print as different strings because they use different encoding schemes. Swapping `derivePeerId()` for the real libp2p encoding is a cosmetic follow-up; it is not a correctness issue.

---

## M2a — PQXDH + Double Ratchet, proven in isolation ✅

Uses the actual Signal Protocol library (`org.signal:libsignal-client` v0.94.0+, the same code Signal's own apps run), proving PQXDH session establishment and Double Ratchet encryption work correctly, entirely in memory. No networking — Alice and Bob's identities both live in one process; a real peer connection is wired in M2b/M2c.

> **PQXDH, not X3DH:** the current Signal Protocol is PQXDH (Post-Quantum Extended Triple Diffie-Hellman), which adds a mandatory Kyber-1024 post-quantum key exchange alongside the classic elliptic-curve keys. The architecture spec's original "X3DH" references are effectively PQXDH now. The `core-crypto` implementation already reflects this.

> **Licensing:** `libsignal-client` is AGPL-3.0 licensed. This is a deliberate, informed choice — using it during development without distributing the app carries no practical obligation. If this project is ever distributed publicly, AGPL's terms apply from that point. This must be revisited before any public release.

```bash
./gradlew :node-daemon:runCryptoDemo
```

Expected output confirms:
- Session established using the PQXDH pre-key bundle
- Alice's first message used the `PREKEY` handshake-carrying format
- Bob decrypted it correctly (implicitly completing his side of the session)
- Bob's reply used the plain `WHISPER` ratchet format (no handshake needed)
- Alice decrypted that correctly — ending in `M2a CONFIRMED`

### Deliberately not in M2a

- No connection to real network peers — that is M2b (custom protocol) and M2c (wiring crypto through it).
- No persistent session storage — `InMemorySignalProtocolStore` forgets everything on process exit. SQLite-backed session storage (`docs/architecture-spec.md §9`) comes later.
- No `SecureSessionService` abstraction yet at this stage — the demo uses libsignal's classes directly. The clean interface (`SecureSessionService`) is built in M2c once it has a real network caller to serve.

---

## M2b — Custom libp2p protocol between real peers ✅

A new libp2p protocol, `/p2p-chat/envelope/0.1.0`, registered alongside `ping` on the same host. Unlike `ping`, this one carries **arbitrary application bytes** — the pipe that PQXDH handshakes and Double-Ratchet ciphertext travel over in M2c.

`core-network` additions:
- `EnvelopeController` — the caller-facing handle: exposes `send(byte[])`.
- `OnEnvelopeMessage` — callback interface `onMessage(PeerId sender, byte[] data)`.
- `EnvelopeProtocol` — the `ProtocolHandler<EnvelopeController>` implementation, structured to mirror jvm-libp2p's own `Ping` protocol and the official "chatter" example exactly.
- `EnvelopeBinding` — the `StrictProtocolBinding` wrapper that registers the protocol ID with the host.

`runListener` now also prints any envelope message it receives (decoded as UTF-8 text). `runPinger` sends one after its usual 5 pings:

```bash
# terminal 1 (unchanged)
./gradlew :node-daemon:runListener -Pdatadir=.p2p-chat-data-listener

# terminal 2
./gradlew :node-daemon:runPinger -Pdatadir=.p2p-chat-data-pinger -Paddr="/ip4/127.0.0.1/tcp/9000/p2p/..."
```

Expected listener output:
```
[envelope] received from <peer-id>: "Hello from the pinger — M2b envelope test."
```

**A design note on dependency scoping:** `OnEnvelopeMessage`'s callback signature (`onMessage(PeerId sender, byte[] data)`) exposes a type from `jvm-libp2p` through `core-network`'s public API. The dependency in `build.gradle.kts` must therefore be declared as `api(...)` with the `java-library` plugin applied — the textbook-correct rule is that any dependency whose types appear in your public API surface must be `api`, not `implementation`. This is already applied.

### Deliberately not in M2b

- No application-level framing yet — raw bytes only. The `EncryptedFrameCodec` that adds a type-marker byte (PreKey vs. Whisper) is introduced in M2c.
- No two-way reply path on the sender side — the sender's `EnvelopeBinding` uses a no-op incoming callback; a real reply path requires resolving a peer ID back to an address, which is M3's job.

---

## M2c — End-to-end encryption over a real connection ✅

Identity (M1.5), network transport (M1/M2b), and real Signal Protocol encryption (M2a) all meeting for the first time: two real processes, over a real libp2p TCP connection, exchanging an actual PQXDH-established, Double-Ratchet-encrypted message.

**New in `core-crypto`:**
- `SignalIdentity` / `SignalIdentityVault` — a **separate, persistent** Signal identity (Curve25519 / `IdentityKeyPair`) stored alongside the network identity. Signal's key format is distinct from core-identity's Ed25519 key and there is no safe direct conversion, so these are intentionally two separate identities. The vault persists `signal-identity.key` and `signal-identity.reg` in the data directory.
- `PreKeyBundleFactory` — builds a fresh PQXDH bundle (EC prekey + signed EC prekey + Kyber-1024 prekey), storing the corresponding private material in the Signal protocol store.
- `PreKeyBundleCodec` — serializes / deserializes a `PreKeyBundle` to bytes (using length-prefixed fields matching the 11-arg constructor order, since libsignal's `PreKeyBundle` is a JNI-backed native object with no built-in serialization).
- `EncryptedFrame` / `EncryptedFrameCodec` — a 1-byte type marker (`0x01` = PreKey, `0x02` = Whisper) prepended to the serialized ciphertext. Using an explicit marker we control rather than trying to infer the message type from libsignal's internal wire format.
- `SecureSessionService` / `LibsignalSecureSessionService` — the `SessionCryptoService` abstraction the original architecture spec envisioned, now with a real caller.

**Bundle exchange is manual and out-of-band, by design.** The listener writes its pre-key bundle to `published-bundle.b64` in its data directory; the sender reads it via `-Pbundlefile`. Real bundle discovery (so peers don't need to hand-carry a file) is M3's responsibility — keeping this milestone focused on proving the crypto-over-network path works.

**One-directional, by design.** The sender encrypts and sends; the listener decrypts and prints. A full reply would require the listener to dial the sender back, which means resolving a peer ID to a reachable address without already knowing it — that is the peer-routing problem M3 solves.

```bash
# terminal 1
./gradlew :node-daemon:runSecureListener -Pdatadir=.p2p-chat-data-listener
# note the printed network address AND the bundle file path

# terminal 2
./gradlew :node-daemon:runSecureSender -Pdatadir=.p2p-chat-data-sender \
    -Paddr="/ip4/127.0.0.1/tcp/9000/p2p/..." \
    -Pbundlefile="../node-daemon/.p2p-chat-data-listener/published-bundle.b64" \
    -Pmessage="Hello over a real encrypted channel"
```

Expected listener output:
```
[secure] decrypted from <peer-id>: "Hello over a real encrypted channel"
```

That line means: a real PQXDH handshake completed between two independent processes, a real Double Ratchet session encrypted the message, it crossed an actual libp2p connection, and the exact plaintext came out the other side.

### Deliberately not in M2c

- No persistent sessions — a session lives only as long as the process does. SQLite-backed session storage (`docs/architecture-spec.md §9`) is a later milestone.
- No bundle discovery — deferred to M3.
- No reply path — deferred to M3.
- No group sessions — sender-key group encryption (`docs/architecture-spec.md §11`) is M8 (see the milestone reordering note near the top of this file).

---

## M3a — Relay mechanism ✅

A peer with no direct connection to another peer can still reach them via a relay server that both can reach — the actual answer to jvm-libp2p lacking hole-punching (see `docs/architecture-spec.md §10`). A relay connection is not a workaround — it is the correct foundation, because real hole-punch protocols (DCUtR) require a relay connection as their own signaling prerequisite anyway.

**New module: `relay-server`** — a standalone, deployable process. Anyone can run one. Uses `core-identity` for a stable address across restarts, identical to `node-daemon`.

**New in `core-network`:**
- `RelayFrame` / `RelayFrameCodec` — the wire format for relay messages. A single `RelayFrame` record serves both directions: when `isForwardRequest` is true, `peerId` is the **target** (client → relay); when false, `peerId` is the **original sender** (relay → client). The receiver reads the sender from the frame's own field, not from the connection-level `sender` parameter — because on a delivery, the connection-level sender is the relay itself, not the original peer.
- `RelayController` — handle for sending frames over a live relay stream.
- `RelayEventHandler` — two callbacks: `onConnected(PeerId, RelayController)` (fires when the stream activates — the hook for capturing a controller to reuse) and `onFrame(PeerId, RelayFrame)` (fires on every incoming frame).
- `RelayProtocol` / `RelayBinding` — the `ProtocolHandler` implementation for `/p2p-chat/relay/0.1.0`, structurally identical to `EnvelopeProtocol` but with long-lived connections rather than one-shot sends.

**In `relay-server`:** `RelayRegistry` implements `RelayEventHandler`. On `onConnected`, it stores the controller keyed by the connecting peer's libp2p peer ID. On `onFrame`, it looks up the target and forwards the payload as a delivery frame carrying the original sender's ID. Peers that are not currently connected get a "no route" log and the message is dropped.

`PeerNetworkService.start()` gained a new 4-arg overload accepting a `RelayEventHandler` — the existing 3-arg version is completely untouched.

```bash
# terminal 1 — the relay itself
./gradlew :relay-server:run
# note the printed relay address

# terminal 2 — a peer that wants to be reachable via relay
./gradlew :node-daemon:runRelayRegister -Pdatadir=.p2p-chat-data-b -Prelay="/ip4/127.0.0.1/tcp/9100/p2p/..."
# note the printed libp2p peer ID — this is the -Ptarget value below

# terminal 3 — a peer with NO direct connection to terminal 2, reaching it anyway
./gradlew :node-daemon:runRelayForward -Pdatadir=.p2p-chat-data-a \
    -Prelay="/ip4/127.0.0.1/tcp/9100/p2p/..." \
    -Ptarget="<peer ID printed by terminal 2>" \
    -Pmessage="Hello through a relay, no direct connection at all"
```

Expected terminal 2 output:
```
[relay] delivered from <peer-id>: "Hello through a relay, no direct connection at all"
```

### Deliberately not in M3a

- **No direct-first fallback** — every message goes through the relay, even when a direct connection would have worked. The decision logic is M3b.
- **No discovery** — both peers still need to hand-carry the relay address. Real discovery is M3c.
- **No deregistration on disconnect** — `RelayProtocol`'s `onClosed` is a no-op; a vanished peer stays in the registry until the relay process restarts.

---

## M3b — Direct-first, relay-as-fallback ✅

The actual decision logic: try a direct connection first, fall back to relay only if that fails or times out. The relay (M3a) is used when it is actually needed, not always. The `ConnectivityStatus` (`DIRECT` / `RELAYED` / `UNREACHABLE`) is always surfaced — never silently assumed.

**New in `core-network`:**
- `ConnectivityStatus` — the three possible outcomes of a send attempt.
- `ConnectionStrategy` — orchestrates the two-path attempt. `sendEnvelope` with a timeout is tried first on the direct address (if provided); on any failure, the relay path is attempted. Always returns a status rather than throwing for connectivity reasons, so callers always get a definitive answer.

**`PeerNetworkService.sendEnvelope()` gained a timeout-aware 3-arg overload** — `getController()` returns a genuine `java.util.concurrent.CompletableFuture`, so the timed `.get(long, TimeUnit)` JDK overload is available directly. The existing 2-arg version is untouched.

```bash
# terminal 1 — a normal M1 listener, to prove the direct path
./gradlew :node-daemon:runListener -Pdatadir=.p2p-chat-data-direct

# terminal 2 — a relay, to prove the fallback path
./gradlew :relay-server:run

# terminal 3 — a peer registered with the relay, as the fallback target
./gradlew :node-daemon:runRelayRegister -Pdatadir=.p2p-chat-data-fallback -Prelay="/ip4/127.0.0.1/tcp/9100/p2p/..."

# terminal 4a — test the DIRECT path (valid address, terminal 1's)
./gradlew :node-daemon:runReachPeer -Pdatadir=.p2p-chat-data-sender \
    -Pdirectaddr="/ip4/127.0.0.1/tcp/9000/p2p/<terminal 1's peer ID>" \
    -Prelay="/ip4/127.0.0.1/tcp/9100/p2p/..." -Ptarget="<irrelevant for this test>"

# terminal 4b — test the FALLBACK path (a closed port — instant refusal on loopback)
./gradlew :node-daemon:runReachPeer -Pdatadir=.p2p-chat-data-sender \
    -Pdirectaddr="/ip4/127.0.0.1/tcp/59999/p2p/<anything>" \
    -Prelay="/ip4/127.0.0.1/tcp/9100/p2p/..." -Ptarget="<terminal 3's peer ID>"
```

4a should print `Result: DIRECT`, fast. 4b should print `Result: RELAYED` (after however long the closed-port connection takes to refuse — near instant on loopback), with terminal 3 showing a matching `[relay] delivered` line.

### Deliberately not in M3b

- **No distinction between failure reasons** — a malformed address and a genuinely unreachable peer are both "direct failed, try relay." Noted directly in `ConnectionStrategy`'s code as a known simplification.
- **No discovery** — the caller still needs to know a direct address, a relay address, and the target's peer ID. That is M3c.

---

## M3c — Real peer discovery ✅

A dedicated publish/lookup protocol, co-located with `relay-server` (the same "always-reachable third party" pattern already established for relaying). Peers publish their reachable address(es); other peers look them up by peer ID, instead of copy-pasting multiaddr strings between terminals.

**Protocol shape:** Structured on jvm-libp2p's own `Ping` implementation (Initiator/Responder split, asymmetric roles, timeout + connection-close cleanup). The key difference from Envelope/Relay: discovery is a **correlated request-response** protocol — one side asks, the other answers — which is Ping's shape, not Envelope's fire-and-forget or Relay's asynchronous delivery. Simplified from Ping: discovery dials fresh per operation (one-shot-dial convention matching `sendEnvelope`/`pingPeer`), so only one pending request per connection is possible by construction, making a single `AtomicReference` field correct rather than a full correlation map.

**New in `core-network`:**
- `DiscoveryFrame` / `DiscoveryFrameCodec` / `DiscoveryMessageType` — wire format with four message types: `PUBLISH`, `LOOKUP`, `LOOKUP_RESPONSE_FOUND`, `LOOKUP_RESPONSE_NOT_FOUND`.
- `DiscoveryController` — the caller-facing handle: `publish(byte[])` and `lookup(String targetPeerId) → CompletableFuture<DiscoveryLookupResult>`.
- `DiscoveryRequestHandler` — the server-side callback interface: `onPublish(PeerId, byte[])` and `onLookup(String) → byte[]`.
- `DiscoveryProtocol` / `DiscoveryBinding` — the `ProtocolHandler` implementation for `/p2p-chat/discovery/0.1.0`.

**Concurrency:** The `Initiator`'s pending lookup is tracked with an `AtomicReference<CompletableFuture<DiscoveryLookupResult>>`. Both the Netty I/O thread (incoming response) and the timeout scheduler thread need to atomically "claim" the pending future — `getAndSet(null)` makes the claim atomic instead of a two-step check-then-clear that would be a genuine TOCTOU race.

**In `relay-server`:** `DiscoveryRegistry` implements `DiscoveryRequestHandler`. It stores each publisher's record as opaque bytes keyed by their libp2p peer ID — the registry does not interpret the payload, so future extensions (e.g., carrying a `PreKeyBundle` alongside network addresses) add a new codec without changing the registry.

**Only `relay-server` registers the Discovery protocol** — regular peers only ever *dial* a discovery server, and dialing a protocol depends on what the remote accepts, not what is registered locally. Ordinary `node-daemon` call sites are completely untouched.

```bash
# terminal 1 — relay + discovery server (same process, per M3a's co-location)
./gradlew :relay-server:run

# terminal 2 — publishes its address and listens
./gradlew :node-daemon:runPublishRecord -Pdatadir=.p2p-chat-data-published \
    -Pdiscovery="/ip4/127.0.0.1/tcp/9100/p2p/..."
# note the printed libp2p peer ID (visible in the /p2p/... of its own listen address)

# terminal 3 — looks it up with NO address hand-carried, just the peer ID
./gradlew :node-daemon:runLookupPeer -Pdatadir=.p2p-chat-data-lookup \
    -Pdiscovery="/ip4/127.0.0.1/tcp/9100/p2p/..." \
    -Ptarget="<terminal 2's libp2p peer ID>"
```

Success: terminal 3 prints terminal 2's actual address(es), found via the peer ID alone.

### Deliberately not in M3c

- **PreKeyBundle discovery** — currently only network addresses are published. Bundle discovery adds a new cross-module concern (relay-server would need to carry bundle bytes opaquely) that is worth its own pass.
- **No record expiry** — a published record stays on file until the relay process restarts, even if the publishing peer has long since gone offline.
- **No signature on published records** — nothing stops a peer from publishing bogus data. Low risk today since only a network address is published (worst case: a failed connection attempt). This becomes security-critical once bundle discovery is added.

---

## M3d — PeerId unification + storage scaffold ✅

Not a user-visible milestone — a foundation pass, done ahead of M4 specifically because "chunked, **resumable**" file transfer only means something if chunk-received state survives a process restart. Building M4 against in-memory state first would mean either throwing that work away once storage arrived, or quietly designing M4 around memory's constraints instead of disk's. Two things landed together:

**1. One canonical `PeerId` type, everywhere.** Previously, `core-identity`'s `Identity.peerId()` (SHA-256 hex) and `io.libp2p.core.PeerId` (base58 multihash) were both floating around, and `core-network`'s public callback interfaces (`OnEnvelopeMessage`, `RelayEventHandler`, `DiscoveryRequestHandler`) exposed the latter directly — meaning every consumer needed jvm-libp2p on its own compile classpath just to implement a callback. A new module, `core-model`, holds `PeerId` and `DeviceId` (both plain records, matching what `docs/architecture-spec.md §4` already specified). `core-network`'s three callback interfaces now use `com.p2pchat.model.PeerId`, converted from libp2p's type at exactly one point per protocol (`EnvelopeProtocol`, `RelayProtocol`, `DiscoveryProtocol`, `Libp2pNetworkService`). Because `io.libp2p.core.PeerId` no longer appears anywhere in `core-network`'s public API, its `jvm-libp2p` dependency correctly went back to `implementation` (it was `api` only because that leak required it).

**This unifies the type, not the value space.** `core-identity`'s hex ID and the libp2p-derived base58 ID are still two different *values* from the same key (documented back in M1.5) — reimplementing libp2p's exact peer-ID derivation by hand to make them match was judged too risky to do blind (no way to verify it against the real library without the ability to compile against it), and giving `core-identity` a dependency on jvm-libp2p was ruled out back in M0 for good reason. What changed is that every module boundary now agrees on one Java type instead of a mix of `String` and `io.libp2p.core.PeerId`.

**2. `core-storage` — SQLite, migrations, and `StorageService`.** New module, dependency-wise downstream only of `core-model`. `V001__init.sql` creates the full schema from `docs/architecture-spec.md §9` (all eight tables), applied via a small hand-rolled `MigrationRunner` (versioned, idempotent — tracks applied versions in a `schema_migrations` table, safe to run against an already-migrated database). `StorageService` matches the five-method interface `§5` already sketched — `saveMessage`, `queryMessages`, `saveContact`, `saveFileMetadata`, `runInTransaction` — backed by `SqliteStorageService` and the `org.xerial:sqlite-jdbc` driver (Apache 2.0, Maven Central, no extra repository needed).

**One spec correction made along the way:** `§9`'s `messages` table was missing a `sender_device_id` column, even though `§4`'s `Message` record and `§13` both describe every message as carrying one. Treated as an omission and added in `V001__init.sql` (noted inline there) rather than left out.

```bash
./gradlew :node-daemon:runStorageDemo -Pdatadir=.p2p-chat-data-storage
# Opens (and migrates) .p2p-chat-data-storage/p2p-chat.sqlite, saves a contact, a message,
# and file-transfer metadata, queries the message back, and does one transactional write.
# Safe to re-run — random IDs each run, and migration is idempotent either way.
```

### Deliberately not in M3d

- **No chunk-level (`file_chunk_state`) methods on `StorageService`.** The table exists; deciding what a resumable transfer actually needs to read/write from it is M4's design work, not this scaffold's — adding methods now would mean guessing at an API shape M4 hasn't designed yet.
- **No conversation/group/CRDT methods.** `conversations`, `conversation_members`, and `crdt_ops_log` tables exist (per `§9`), but `StorageService` stays at exactly the five methods `§5` already specified. *(As of M4e: `saveConversation` closes the narrow foreign-key gap this created, but real conversation/membership management is still M5's territory, and group/CRDT methods are still M8's — see the M4e section below.)*
- **No test suite.** Still true project-wide, not new to M3d — see the housekeeping note below.
- **`.gitignore` still doesn't match the `.p2p-chat-data-*` test-run variants.** Flagged, deliberately left as-is — not worth doing until this repo is actually about to be `git init`'d.

---

## M4a — Chunking + per-chunk AES-256-GCM encryption, proven in isolation ✅

First piece of M4 (file transfer). Same pattern as M2a: prove the new primitive works correctly, alone, before wiring it to networking or storage. New module, `core-filetransfer` — zero dependencies, since chunking and AES-256-GCM need nothing beyond the JDK (`javax.crypto`, `java.security.MessageDigest`, `java.nio.file`).

Implements `docs/architecture-spec.md §12` steps 1, 2, and 5: `FileChunker` splits a file into fixed-size chunks (256 KB default) and computes SHA-256 hashes (both per-chunk, for content-addressing in step 4, and whole-file, for the completion check in step 5); `FileKey` models the random per-file AES-256 key step 2 describes; `ChunkCipher` encrypts/decrypts each chunk with a fresh random 12-byte nonce per call, matching `FileChunkPayload`'s explicit `nonce` field in the `§6` `.proto` sketch.

**This one was verified more directly than anything before it.** This sandbox doesn't normally have a JDK available for compiling code that depends on jvm-libp2p or libsignal-client — those live behind Maven repositories outside what this environment can reach, so M0–M3d were built by carefully tracing every call site by hand (later confirmed correct by your test results). `core-filetransfer` has zero external dependencies, so it was actually compiled and run here — including a deliberate tamper test (flip one bit in a chunk's ciphertext, confirm decryption throws rather than silently returning corrupted data), proving GCM's authentication genuinely catches corruption instead of just assuming it does.

```bash
./gradlew :node-daemon:runFileTransferDemo
# Chunks a small demo file (16-byte chunks, so a short file produces several), encrypts each
# chunk, decrypts and reassembles them, verifies the reassembled SHA-256 matches the original,
# and confirms a tampered chunk is correctly rejected.
```

### Deliberately not in M4a

- **No networking.** Nothing here talks to a peer yet — that's M4c.
- **No wire format for `FileOfferPayload` / `FileChunkRequestPayload` / `FileChunkPayload`.** That's M4b, immediately below.
- **No per-recipient file-key wrapping.** `§8` describes wrapping the file key "individually per-recipient via their session" — that needs `core-crypto`'s `SecureSessionService`, which `core-filetransfer` deliberately doesn't depend on yet.
- **No `core-storage` integration.** `FileTransfer`/chunk-state persistence (M3d) isn't wired in here — mirrors M2a not touching networking either. Comes together once there's an actual transfer to persist state *about*.
- **No swarm/multi-recipient piece-serving.** `§12` step 4's `bt`-library-based swarm logic is explicitly for *group* file shares — since groups don't exist yet (M8), there's nothing to swarm between. This project's own M4 milestone name is "file transfer: **single-peer**, chunked, resumable" — swarm logic is out of scope for M4 entirely, not just M4a, and should be revisited if/when M8 lands.

---

## M4b — File-transfer wire payloads, proven in isolation ✅

Second piece of M4. `docs/architecture-spec.md §6`'s `.proto` sketch defines `FileOfferPayload` and `FileChunkPayload`, but not `FileChunkRequestPayload` — `EnvelopeType.FILE_CHUNK_REQUEST` exists there, but no corresponding message does. That gap is filled here (`{transferId, missingChunkIndices[]}`, directly matching `§12` step 3's description: "a fresh request just skips chunks already marked received" — an empty array means "I already have everything").

Design note settled before writing any of this: the spec's full `Envelope` protobuf (`message_id` dedup, `hlc_timestamp` ordering, `sender_device_id`) isn't built yet anywhere in this project — even M2c/M3c's demos still send raw unstructured bytes. File transfer doesn't actually need those fields for its own correctness (`transfer_id` + `chunk_index` already provide the idempotency and ordering `message_id`/`hlc_timestamp` exist to give chat messages), so this uses a narrow, file-transfer-specific type discriminator instead of that fuller layer — reusing the exact numeric values `§6`'s `EnvelopeType` enum already assigned (`FILE_OFFER=6`, `FILE_CHUNK_REQUEST=7`, `FILE_CHUNK=8`) so nothing needs renumbering if/when M5 eventually needs its own message kinds alongside these. Extracting a *shared* cross-module dispatch mechanism is deliberately not done here either — there's exactly one consumer of this concept right now (file transfer); generalizing for a second, hypothetical consumer (chat, in M5) before it exists would be guessing at a shape nobody's asked for yet.

Wire format matches `core-network`'s established `RelayFrameCodec`/`DiscoveryFrameCodec` convention exactly: 1-byte marker, `[4-byte length][UTF-8 bytes]` for strings, fixed-size fields for everything else, "whatever's left" for the trailing ciphertext blob — no new encoding style introduced.

**Also compiled and run directly**, like M4a — `core-filetransfer` still has zero external dependencies, so nothing here needed to be hand-traced. 21 checks: full round-trips of all three payload types, a Unicode filename, an empty chunk-index array (the "nothing missing" case), a zero-length ciphertext, and two "malformed input correctly rejected at encode time" checks (wrong-length file key, wrong-length nonce).

```bash
./gradlew :node-daemon:runWireCodecDemo
# Runs all 21 checks, printing [PASS]/[FAIL] for each, ending in M4b CONFIRMED.
```

### Deliberately not in M4b

- **No networking, no crypto sessions.** These payloads aren't sent anywhere yet, and nothing here calls `SecureSessionService`. That's M4c.
- **No shared cross-module message-type dispatch.** See the reasoning above — deferred until M5 gives a second real consumer.
- **No `core-storage` integration.** Same reasoning as M4a.

---

## M4c — Real, single-peer file transfer over an encrypted connection ✅

Third piece of M4, and the first genuinely new *shape* of milestone: every prior networked demo (M2c, M3a–c) was one-shot — one side dials out, sends, done. This one needs real back-and-forth: the receiver has to reply with a `FileChunkRequestPayload` on its own initiative, then receive a stream of `FileChunkPayload`s after that. Two things were checked directly against the actual `core-crypto`/`core-network` source before writing any of this, rather than assumed:

- `LibsignalSecureSessionService` holds one `SignalProtocolStore` for its whole lifetime, and `SecureListenerMain` already creates that service once and closes over it in its callback — so a later `encrypt()` call correctly finds a session an earlier `decrypt()` established. Bidirectional exchange over one session is architecturally sound.
- `PeerNetworkService.sendEnvelope`'s own Javadoc confirms it's symmetric — any node with a running host can dial out and send, regardless of whether it's the side that "listened" first. A receiver replying to an offer is the same operation `SecureSenderMain` already does, not a new capability.

Both of those held up — **the first real test correctly established a PQXDH session and decrypted the offer** (`[file] offer received: "m4c-test-file.txt"...`), which is the part I flagged as genuinely unproven going in. What broke was something else entirely, and it's worth being straightforward about: a real design bug, not a networking or crypto issue.

**The bug:** the first version of `FileReceiverMain` required the sender's address as a startup argument, so it could dial back to deliver the chunk request. But the documented workflow was "start the receiver first" — which is exactly the moment the sender's address can't be known yet, since the sender hasn't started. The only way to run it was to pass a placeholder, which then blew up the moment the receiver actually tried to reply (`Malformed multiaddr: '.../p2p/placeholder'`). A real chicken-and-egg bug, caught by your test exactly the way it should be.

**The fix:** rather than patch around it, remove the need for it. `FileOfferPayload` now carries a `senderAddress` field — the sender reports its own address (`network.listenAddresses()[0]`), inside the encrypted, authenticated offer itself. `FileReceiverMain` no longer takes any address as a startup argument at all; it learns where to reply from the offer when it arrives. This is a wire-format change (documented in `FileTransferMessageCodec`'s Javadoc and re-verified: 22 round-trip checks now, up from 21, with a new `FileOffer.senderAddress` check added), but nothing was deployed anywhere, so there's no compatibility concern — just a cleaner design than the one that shipped first.

```bash
# Terminal 1 — safe to start first now, with no prior knowledge of the sender needed:
./gradlew :node-daemon:runFileReceiver -Pdatadir=.p2p-chat-data-receiver

# Terminal 2 — point it at a real file on your machine:
./gradlew :node-daemon:runFileSender \
    -Paddr="<receiver's network address, printed by runFileReceiver>" \
    -Pbundlefile="<path to the receiver's published-bundle.b64, printed by runFileReceiver>" \
    -Pfile="<path to any small local file>" \
    -Pdatadir=.p2p-chat-data-sender
```

`FileReceiverMain` plays `SecureListenerMain`'s original role (publishes a bundle, waits, decrypts the first PreKey-carrying message) but then, unlike M2c's listener, actively replies — requesting every chunk (no resume yet) and decrypting/reassembling each `FileChunkPayload` as it arrives, verifying the final SHA-256 once complete. `FileSenderMain` plays `SecureSenderMain`'s original role (establishes the session, sends first) but then stays running afterward — like the listener — to receive and answer the chunk request. Both build the remote `SignalProtocolAddress` fresh from each callback's actual `sender` parameter, matching `SecureListenerMain`'s proven pattern, rather than from a precomputed value.

### Deliberately not in M4c

- **No resume.** Every offer gets a request for all chunks, every time. That's M4d.
- **No `core-storage` integration.** Transfer state lives in an in-memory `Map` inside each Main class, same scope boundary as M4a/M4b.
- **No delivery/accept UX.** The receiver auto-accepts every offer instantly. Spec's `acceptTransfer()` (§5's `FileTransferService` sketch) implies a real accept/reject step; not needed to prove the transfer mechanics work.

---

## M4d — Chunk-level resume via `core-storage` ✅

Fourth and final piece of M4. Extends `StorageService` with `markChunkReceived`/`missingChunks`, and changes `FileReceiverMain` to write each chunk directly to its correct byte offset in the output file as it arrives (not accumulate in memory), so a restart has actual bytes to resume *into*, not just a boolean saying which ones it could skip re-requesting.

**This one could be compiled and run directly too, for the first time since M4a/M4b** — `sqlite-jdbc` doesn't need Maven Central specifically, just *a* place to get the jar, and it turns out GitHub Releases works fine within this sandbox's allowed domains even though Maven Central doesn't. Real SQLite, real foreign key enforcement, a genuinely simulated process restart (one `SqliteDatabase` closed, a completely new instance opened against the same directory, no in-memory state carried over) — not hand-traced.

**That direct testing caught a real bug on the first attempt**, the same way your M4c test caught the address bug: `file_chunk_state` has a foreign key on `file_transfers` (`§9`), so `markChunkReceived` throws unless `saveFileMetadata` was already called for that transfer. The fix has two parts — `FileReceiverMain` now saves metadata before touching chunk state, and `saveFileMetadata` had to become an upsert (`INSERT OR IGNORE`) rather than the plain insert M3d designed, since a resumed transfer legitimately calls it again for a `transferId` that's already stored.

**A discrepancy worth your attention, which I'm flagging rather than quietly fixing:** while investigating the above, I found that `messages` has the exact same kind of foreign key (`conversation_id REFERENCES conversations`), and `StorageService` has no method that can ever create a `conversations` row. Testing this directly here, `saveMessage` fails with a foreign key violation under the same conditions M3d's `StorageDemoMain` already exercised successfully on your machine. I can't explain why your earlier test passed — genuinely don't know, and didn't want to guess and present a theory as fact. It's not blocking anything right now (nothing calls `saveMessage` for real yet — `StorageDemoMain` is the only caller, and it's a demo), but it's worth resolving before M5's messaging work actually depends on it. Re-running `runStorageDemo` on your end would tell us whether this is a real, reproducible gap or something specific to my sandbox.

> **Resolved in M4e (see below):** it was not a sandbox discrepancy. `StorageDemoMain` created its `conversations` row with a raw JDBC `INSERT`, bypassing `StorageService` entirely — which satisfies SQLite's foreign key just as well as a row created through the service layer, so the demo was quietly working around the gap rather than exercising it. Both runs were correct given what each was actually doing; there was no real disagreement between sandboxes.

**Also new: two testing conveniences**, so this could be verified as a real two-process resume, not just my isolated demo:
- `runFileSender` takes an optional `-Pchunksize=<bytes>` to force a small test file into multiple chunks (the default 256 KB means a small file is always exactly 1 chunk).
- `runFileReceiver` takes an optional `-Pexitafter=<chunk index>` that exits the process right after that chunk is durably written and marked received — simulating a crash mid-transfer on purpose.

```bash
# Isolated proof — no networking, mirrors every prior milestone's "prove it alone first" demo:
./gradlew :node-daemon:runChunkResumeDemo

# Real two-process resume test:
# Terminal 1 — exits deliberately right after chunk 1:
./gradlew :node-daemon:runFileReceiver -Pdatadir=.p2p-chat-data-receiver -Pexitafter=1

# Terminal 2 — small chunks so a small file has several:
./gradlew :node-daemon:runFileSender \
    -Paddr="<receiver's address>" -Pbundlefile="<receiver's published-bundle.b64>" \
    -Pfile="<a small local file>" -Pchunksize=16 -Pdatadir=.p2p-chat-data-sender

# Once the receiver exits, restart it with the SAME data dir and re-run the sender command
# (same file, same chunk size) — the receiver should report resuming with only the chunks it
# doesn't already have, and the sender should serve only those, not the whole file again.
./gradlew :node-daemon:runFileReceiver -Pdatadir=.p2p-chat-data-receiver
./gradlew :node-daemon:runFileSender \
    -Paddr="<receiver's address>" -Pbundlefile="<receiver's published-bundle.b64>" \
    -Pfile="<the same file>" -Pchunksize=16 -Pdatadir=.p2p-chat-data-sender
```

### Deliberately not in M4d

- **No `TransferState` updates.** A transfer's `state` column is set to `IN_PROGRESS` when the offer arrives and never updated to `COMPLETED` — that needs an update capability `StorageService` doesn't have yet, and designing that felt like a separate decision from "does resume work," not a blocker for it.
- **The sender has no resume state of its own.** It doesn't need any — it re-reads the requested chunk indices from the original source file on disk every time, which is already durable regardless of process restarts. Resume is fundamentally a receiver-side concern, matching how the spec frames it (§15: "peer goes offline mid-file-transfer").
- **The `messages`/`conversations` foreign key discrepancy above isn't fixed here.** It's flagged, not resolved yet — **fixed in M4e, immediately below.**

---

## M4e — `conversations`/`messages` foreign-key fix ✅

Resolves the discrepancy M4d flagged and left open, ahead of M5 (which is exactly where `saveMessage` gets a real caller for the first time, per the milestone reordering note above).

**The actual explanation, not just a fix.** M4d's investigation couldn't explain why `StorageDemoMain` appeared to exercise `saveMessage` successfully on real hardware despite `StorageService` having no method that could ever create a `conversations` row — it was flagged rather than guessed at. Reading `StorageDemoMain` and `SqliteStorageServiceTest` directly against this question resolves it completely: both created their `conversations` row with a **raw, hand-written JDBC `INSERT`**, bypassing `StorageService` entirely, before ever calling `saveMessage`. SQLite's foreign-key check doesn't care how a row got there — a row inserted via raw SQL satisfies the constraint exactly as well as one inserted through the service layer. So `StorageDemoMain` was never actually exercising the gap it appeared to prove worked; it was quietly working around it. There was no discrepancy between sandboxes — both runs were correct given what each test actually did.

**The fix:** a new `Conversation` model (`core-storage.model`) and `StorageService.saveConversation(Conversation)`, upsert semantics (`INSERT OR IGNORE`) matching `saveFileMetadata`'s existing pattern — a caller must be free to call this before every `saveMessage` without checking existence first, since a 1:1 conversation is naturally re-seeded on every send rather than created exactly once. `StorageDemoMain` and `SqliteStorageServiceTest` were both updated to call `saveConversation` instead of hand-writing SQL, so they now actually exercise the real path a caller will use.

**Deliberately narrower than `docs/architecture-spec.md §4`'s `Conversation` sketch** — no `members` field. Membership lives in its own table (`conversation_members`) with its own read/write access pattern (how a DIRECT conversation's two members get seeded, how a GROUP's roster changes), and that access pattern is real design work that belongs to M5/M8, not something to guess at just to make this record match §4 exactly. This type exists to satisfy one foreign key so `saveMessage` works; it is not the conversation-management API.

**Verified directly, not hand-traced** — `core-storage` remains pure JDK + `sqlite-jdbc` (via GitHub Releases, same as M4d), so this was compiled and run against a real SQLite database rather than reasoned about from source alone. Confirmed three things directly: `saveMessage` against a nonexistent conversation throws `[SQLITE_CONSTRAINT_FOREIGNKEY] ... FOREIGN KEY constraint failed`, exactly as M4d's investigation reported; `saveConversation` (called twice, to prove the upsert is idempotent) followed by `saveMessage` succeeds; and a conversation row created by raw SQL unblocks `saveMessage` exactly as well as one created via `saveConversation` — confirming the masking theory above isn't speculation.

New regression tests (`SqliteStorageServiceTest`) pin both ends of this down directly: `saveMessageFailsWithoutAConversation` and `saveConversationIsIdempotentAndUnblocksSaveMessage`, so this specific gap can't silently reopen.

### Deliberately not in M4e

- **No conversation *listing* or lookup methods.** `saveConversation` only creates a row — there's no `getConversation`/`listConversations` yet, because nothing needs to read one back until M5's messaging flow exists.
- **No `conversation_members` population.** A DIRECT conversation's two participants aren't recorded anywhere yet. `FileReceiverMain`'s placeholder `"direct-" + senderPeerId` conversation IDs are untouched by this fix — `file_transfers.conversation_id` has no foreign key (only `messages` does), so M4's file-transfer path was never actually blocked by this gap in the first place.
- **No group/CRDT methods.** Still M8's territory, unchanged from M4d's scope note.

---

## M5a — HybridLogicalClock, proven in isolation ✅

First piece of M5 (1:1 messaging). Same pattern as M2a and M4a: prove the new primitive works correctly, alone, before wiring it to networking or storage. New module, `core-messaging` — zero dependencies, same reasoning as `core-filetransfer` (M4a): the algorithm needs nothing beyond the JDK (`java.time.Clock`, `java.util.concurrent.atomic`).

**A real deviation from `docs/architecture-spec.md §11`, made deliberately and out loud, not silently.** §11 says to "use an existing HLC implementation rather than hand-rolling one." Before writing any code, I searched for one — properly, not as a formality. HLC implementations are common in JS, Go, Elixir, Kotlin, Dart; there is no maintained, published **Java** library. The closest candidate, `CharlieTap/hlc` (Kotlin Multiplatform, JVM-targeted), has zero GitHub releases and zero published packages — nothing actually reachable via a dependency coordinate, 30 stars, 11 commits. There was nothing legitimate to depend on, so this implements the algorithm directly from its source: Kulkarni, Demirbas, Madeppa, Avva, Leone, *"Logical Physical Clocks"* (OPODIS 2014 / SUNY Buffalo Tech Report 2014-04), Figure 4 — fetched and read directly, not recalled from memory, given how much message ordering depends on getting this exactly right.

`HlcTimestamp` is the immutable `(l, c)` pair the paper defines, plus one documented addition on top of it: a `nodeId` field consulted only as a final tie-break when two timestamps land on the exact identical `(l, c)` — which the paper itself says is expected for genuinely concurrent events (§2), not an error. The paper never needs to break that tie (it only proves things about causally-related pairs, which Theorem 1 guarantees already differ); a SQLite `TEXT` sort column backing a UI's message list does need it, since the UI still has to render *some* deterministic order for two people who typed at the same instant. `HybridLogicalClock` is the stateful per-node clock: `now()` implements "Send or local event", `update(remote)` implements "Receive event of message m", both transcribed against the paper's pseudocode field-by-field.

**One more addition beyond the paper, made deliberately:** thread safety, via a compare-and-swap loop on an immutable `HlcTimestamp` (the same `AtomicReference` idiom M3c already established for `Initiator`'s TOCTOU fix). The paper's algorithm assumes one sequential thread of events per node; this project's actual use is one clock shared across however many concurrent peer sessions a node has running (M6's daemon, not built yet, but the reason this matters now rather than later). A clock that loses updates under concurrent calls would silently violate its own causality guarantee — that's not speculative hardening, it's what "shared per-node clock" already means once M6 exists.

**Verified by actually compiling and running it — including proving the concurrency test itself has teeth.** `core-messaging` has zero external dependencies, so unlike M0–M3d this was compiled and run directly rather than hand-traced. Every branch of the paper's receive algorithm (all four cases), the causality guarantee (Theorem 1, chained across three nodes for transitivity), the counter-growth/reset property Figures 3 and 5 illustrate (built from scratch to exercise the same property, since those are diagrams this document can't reproduce exact numbers from), and the thread-safety claim were each checked directly, not asserted. For the concurrency claim specifically: 32 threads × 300 calls each against a shared clock, asserting all 9,600 results are distinct with no gaps in the counter sequence — run 6 times clean. Then, to make sure that test wasn't just passing by luck, I deliberately broke the implementation (swapped the CAS loop for a plain non-atomic read-then-write) and reran it: **it failed on the 5th of 5 runs**, confirming the test genuinely catches the exact race it claims to, not just a tautology that happens to pass.

```bash
./gradlew :core-messaging:test
# 21 tests across HlcTimestampTest (10: ordering, string round-trip, validation, 500-sample
# random ordering-consistency check) and HybridLogicalClockTest (11: both event procedures, all
# four receive branches, causality, drift/reset, and the 9,600-call concurrency stress test).
```

**Correction, added while building M5b:** every milestone from M0 through M4d has a standalone, human-runnable `node-daemon` demo (`runCryptoDemo`, `runFileTransferDemo`, `runWireCodecDemo`, ...) — M5a shipped without one, an inconsistency with the project's own established convention that went unnoticed until M5b's own demo was being built. Fixed now, not silently: `HlcDemoMain` covers the same ground as the JUnit suite above (both event procedures, all four receive branches, causality, drift/reset, and a 9,600-call concurrency check) in the same `[PASS]`/`[FAIL]` style every other demo Main uses. Compiled and run directly, same discipline as everything else in M5a — 20/20.

```bash
./gradlew :node-daemon:runHlcDemo
```

### Deliberately not in M5a

- **No wire encoding/decoding tied to `Envelope`.** `HlcTimestamp.toString()`/`.parse()` exist and are tested, but nothing here talks to `SecureSessionService` or produces bytes for the wire — that's M5b.
- **No resilience hardening from the paper's §4** — bounded drift checks, rejecting implausible remote timestamps, self-stabilization after corruption. Matters once `update()` is fed values from an untrusted peer over the network; doesn't matter yet for proving the core algorithm correct in isolation. Flagged explicitly so M5b/M5c doesn't have to rediscover this gap.
- **No integration with `core-storage`.** `Message.hlcTimestamp` is a plain `String` already — `HlcTimestamp.toString()`'s output is drop-in compatible — but nothing calls that yet. Mirrors M4a not touching `core-storage` either.

---

## M5b — Chat wire payloads, proven in isolation ✅

Second piece of M5. `docs/architecture-spec.md §6`'s `.proto` sketch already defines `ChatMessagePayload` — but not `DeliveryReceiptPayload` or `ReadReceiptPayload`. `EnvelopeType.DELIVERY_RECEIPT`/`READ_RECEIPT` exist there with no corresponding messages, same shape of gap M4b found and filled for `FileChunkRequestPayload`. Both filled here: `DeliveryReceiptPayload` is per-message (`{conversationId, messageId}`); `ReadReceiptPayload` is watermark-style (`{conversationId, readUpToHlcTimestamp}`) — agreed on explicitly before writing any code, matching how real chat apps handle read receipts and how `HlcTimestamp`'s ordering makes "everything up to X" a natural, efficient query against `messages`' existing `(conversation_id, hlc_timestamp)` index.

**A real deviation from the `§6` sketch, same shape as M4b's `senderAddress` addition.** The sketch's `ChatMessagePayload` has no `message_id`/`hlc_timestamp` fields — the sketch places those on the outer `Envelope` shell instead. That shell has never been built anywhere in this project (M2b/M2c/M4 all carry plaintext bytes with whatever structure the payload itself defines, nothing more), so — exactly like M4b moving `senderAddress` onto `FileOfferPayload` when the sketch implied it belonged "elsewhere" — `message_id` and `hlc_timestamp` moved directly onto `ChatMessagePayload`, since chat's own definition genuinely needs both (dedup, ordering) and there's nowhere else for them to live. `sender_peer_id`/`sender_device_id` did *not* need the same treatment: unlike file transfer's fresh-dial problem, a chat session is already a live, established Double Ratchet session by the time either side sends anything, so `SecureSessionService.decrypt(remote, frame)`'s own `remote` parameter already answers "who sent this" — nothing to add. `conversation_id`, unlike file transfer's, stayed explicit on the wire rather than being left implicit: `messages.conversation_id` (M4e) is a real foreign key that storage must be able to populate with a concrete value, not something safe to defer to an unstated derivation scheme the way `file_transfers.conversation_id` (no FK) was.

**A scope call revisited, and left where it was.** `FileTransferMessage`'s own Javadoc named this fork explicitly: unifying chat and file-transfer message kinds under one shared dispatch mechanism was deferred "until M5 gives a second real consumer." M5 is that consumer now — but the actual trigger for needing shared dispatch (one decrypted byte stream that could be *either* kind, something having to ask "which one is this?") still doesn't exist. Every M4 demo is file-transfer-only; M5's own demo (below) is chat-only. That need becomes real once a single live session has to field both at once — M6's daemon, not this milestone. So: `ChatWireMessage` is its own sealed hierarchy, `ChatMessageCodec` its own independent codec, reusing `§6`'s `EnvelopeType` numbering (`CHAT_MESSAGE=2`, `DELIVERY_RECEIPT=3`, `READ_RECEIPT=4`) but sharing no code with `FileTransferMessageCodec` — not a retrofit of M4's proven code, not a shared abstraction built for a caller that doesn't exist yet.

Wire format matches `FileTransferMessageCodec`'s convention exactly (1-byte marker, `[4-byte length][UTF-8 bytes]` strings, a 1-byte presence flag for the optional `replyToMessageId`) — no new encoding style introduced. `hlcTimestamp`/`readUpToHlcTimestamp` are encoded via `HlcTimestamp.toString()`/`.parse()` directly, building on M5a's already-proven round trip rather than reinventing it. `messageId` and `replyToMessageId` (when present) are validated as real UUIDs at construction time, not just structurally decoded — catching a malformed ID from a bug in calling code before it ever reaches the wire, not just after.

**Verified the same way as M5a** — `core-messaging` still has zero external dependencies, so this was compiled and run directly, including one check M4b's own tests didn't do: reading the actual encoded bytes of a `DeliveryReceiptPayload` back apart by hand (marker byte, then each length-prefixed field) and confirming they match the documented layout exactly, not just that encode-then-decode round-trips.

```bash
./gradlew :core-messaging:test
# 13 tests: full round-trips of all three payload types (including the optional-field-present/
# absent and Unicode/empty-content cases), and 6 validation checks (non-UUID messageId, non-UUID
# replyToMessageId, empty conversationId, null hlcTimestamp — rejected at construction, not
# wire-decode, time).

./gradlew :node-daemon:runChatWireCodecDemo
# Same coverage, standalone, [PASS]/[FAIL] style — 21 checks (some JUnit tests assert more than
# one thing; this demo mirrors them at the assertion level, same relationship M4b's
# runWireCodecDemo has to FileTransferMessageCodecTest).
```

### Deliberately not in M5b

- **No networking, no crypto sessions.** These payloads aren't sent anywhere yet, and nothing here calls `SecureSessionService`. That's M5c.
- **No shared cross-module message-type dispatch with file transfer.** See the reasoning above — the trigger for needing it still doesn't exist; deferred again, past M5, to M6.
- **No `core-storage` integration.** Same reasoning as M4a/M5a — nothing here calls `saveMessage`/`saveConversation` yet. Comes together once there's an actual received message to persist.
- **No message-id dedup logic.** `messageId` is validated as a real UUID and carried on the wire, but nothing checks "have I seen this one before" yet — that's M5d, once there's real storage to check against.

---

## M5c — Real, bidirectional 1:1 chat over an encrypted connection ✅ (verified on real hardware — two real bugs found and fixed on the first test run)

Third piece of M5, and the first milestone to actually wire `core-messaging` (M5a/M5b) and `core-storage`'s `saveConversation`/`saveMessage` (M4e) into a live send/receive loop — `HybridLogicalClock` and the chat wire payloads both get real callers for the first time. Structurally closest to M4c: two new `node-daemon` classes, `ChatListenerMain` and `ChatSenderMain`. Unlike file transfer's offer/request/chunk asymmetry, though, a chat exchange is inherently symmetric, so both classes both receive-and-persist *and* compose-and-send within one connection, proving the round trip in both directions rather than one.

**A real gap in M5b's own design, found before writing any of this — not after, unlike M4c's identical-shaped bug.** Worth being precise about the difference: M4c's `senderAddress` fix (§ above) was caught by *your* real test run — the bug only surfaced by actually executing the code. Before writing `ChatListenerMain`/`ChatSenderMain`, I read `PeerNetworkService.sendEnvelope`'s own Javadoc directly rather than assume: *"a one-shot send: opens a new stream for this call rather than reusing an existing one."* It always dials a fresh multiaddr; it never reuses an inbound connection. M5b's reasoning for leaving `sender_peer_id`/address off `ChatMessagePayload` — "a chat session is already a live, established session" — was true at the crypto layer (`LibsignalSecureSessionService` does hold ratchet state across calls) but wrong at the network layer, where a listener that only knows the sender's peer ID has no way to physically reply. Exactly M4c's chicken-and-egg bug, for the identical reason. **The fix is the same fix:** `ChatMessagePayload` gained a `senderAddress` field, mirroring `FileOfferPayload`'s exactly — the sender reports its own address inside the encrypted, authenticated message. Unlike file transfer, which has one "offer" to concentrate this on, any given chat message could be the first of a fresh connection, so this field lives on every `ChatMessagePayload`, not a one-time handshake payload. M5b's wire format, tests, and demo were all updated and re-verified (23 checks now, up from 21, with new `senderAddress` round-trip and empty-value-rejected checks) — see `ChatMessagePayload`'s own Javadoc for the full account.

**Two more real bugs — genuinely caught by your test run this time, exactly as the verification note below predicted would be possible.**

1. **Wildcard bind addresses aren't dialable.** `network.listenAddresses()[0]` returned `/ip6/::/tcp/9200/p2p/<peer-id>` on your machine (Windows) — the "listening on every interface" wildcard address itself, not a concrete address anything can connect *to*. That value was going straight into `ChatMessagePayload.senderAddress`, so the listener's attempted reply-dial had nowhere real to go. Fixed with a new `firstDialableAddress` helper (both Mains) that resolves a wildcard bind to loopback — correct for this milestone's same-machine demo, explicitly documented as *not* a general fix: two genuinely different physical machines would need real address discovery (a real LAN IP, or STUN-style external discovery), neither of which exists anywhere in this project yet. Flagged in the helper's own Javadoc as a real, open question for M6/M7. Verified directly — this particular piece is pure string manipulation with no jvm-libp2p/libsignal dependency, so unlike the rest of these two classes, it could actually be compiled and run in my sandbox: 7 checks, including your exact observed input (`/ip6/::/tcp/9200/p2p/12D3KooWJ4Fr...` → `/ip4/127.0.0.1/tcp/9200/p2p/12D3KooWJ4Fr...`) byte-for-byte.

> **Amendment (M5d):** `firstDialableAddress` — duplicated identically in `ChatListenerMain` and `ChatSenderMain` — was promoted to `core-network`'s `DialableAddressResolver` once a third caller (`FileSenderMain`, which had never even gotten this fix — see M5d below) needed the identical logic. Behavior is a real improvement, not just a move: it now enumerates actual local network interfaces and resolves a wildcard bind to this machine's real LAN IP, falling back to loopback only when no non-loopback interface exists. This narrows the same-machine-only limitation to same-LAN — it does not close it; see `DialableAddressResolver`'s own Javadoc, and the M5d section below, for exactly what is and isn't fixed.

2. **A real Netty deadlock, and this one genuinely could not have been caught without running it.** `ChatListenerMain` was calling `network.sendEnvelope(...)` synchronously from inside its `OnEnvelopeMessage` callback — which runs on jvm-libp2p/Netty's I/O event loop thread. `sendEnvelope` blocks internally waiting for the new outbound connection to complete, but that connection's own I/O also needs the event loop thread to make progress — the same thread the blocking wait is stuck on. The reply dial could never complete, because the thread that would drive it to completion was the one blocked waiting for it. Nothing in `PeerNetworkService`'s Javadoc said this (it didn't know it either, until now); this is Netty implementation-detail knowledge no amount of interface-reading would surface. Fixed by dispatching the `sendEnvelope` call onto a separate thread via `CompletableFuture.runAsync`, with its own try/catch — `CompletableFuture.runAsync` silently swallows uncaught exceptions in fire-and-forget usage, so that catch isn't optional. **This finding was valuable enough to write back into `core-network`'s own already-proven interface** — `PeerNetworkService.sendEnvelope` and `OnEnvelopeMessage`'s Javadoc both now carry this hazard explicitly, documentation-only, zero behavior change, so M6's daemon (which will call `sendEnvelope` from many more contexts than M5c does) doesn't have to rediscover this the same way.

**Design decisions made explicitly, not defaulted into:**
- **`conversation_id` is derived deterministically and order-independently** — `"direct-" + <lower peer ID> + "-" + <higher peer ID>`, sorted lexicographically — computed identically by both `ChatListenerMain` and `ChatSenderMain` from the same two peer IDs, regardless of who's computing it or who sent first. This isn't just tidiness: unlike file transfer's `"direct-"+senderPeerId` placeholder (harmless — `file_transfers.conversation_id` has no foreign key and is never recomputed), `messages.conversation_id` is a real foreign key (M4e) that every message in an ongoing conversation has to agree on — including, within *one* peer's own local database, its own sent messages and the other side's replies. A non-deterministic or order-dependent scheme would split one conversation across two rows depending on message direction. **Confirmed working as designed** — your test run's logs show both processes independently deriving the identical `direct-12D3KooWJ4Fr...-12D3KooWQD2c...` conversation ID.
- **A correctness point easy to get backwards:** when a message arrives, `HybridLogicalClock.update()` is called — but its *return value* is never what gets persisted as that message's own `hlc_timestamp`. The return value is this node's own new local timestamp for the *receive event itself*, a distinct causal event, used only to correctly advance this node's clock for whatever it timestamps next (the reply). The message's own `hlc_timestamp`, for storage and ordering, is `payload.hlcTimestamp()` exactly as the original sender authored it. Storing the receive-event timestamp instead would mean the same message sorts differently in the sender's history than the receiver's — defeating the entire point of a shared causal clock. Called out explicitly in both Mains' Javadoc, not left as an easy-to-miss implementation detail.
- **`DeliveryState` interpretation for received messages.** The enum (`SENDING, SENT, DELIVERED, READ, FAILED`) is documented as covering "a locally-sent or locally-received message" but doesn't spell out what a *received* message's state means. Interpretation used here, matching how iMessage/Signal/WhatsApp model it locally: for a message this device *sent*, the column tracks the *recipient's* acknowledgment of it (`SENT` here — delivery/read receipts updating it further is M5d). For a message this device *received*, the column tracks *this device's own* consumption of it — `DELIVERED` on arrival (it's on this device now), `READ` only once there's a real user action marking it read, which doesn't exist yet. Not something the schema or spec spelled out; flagged as an interpretation, not asserted as fact.

> **Amendment (M5d):** this interpretation is now load-bearing, not just documented — see the M5d section below for `updateDeliveryState`/`markMessagesReadUpTo`, which implement exactly this "sent messages track the recipient's ack; received messages track this device's own consumption" split.

```bash
# Terminal 1 — safe to start first, no prior knowledge of the sender needed (same reasoning as
# runFileReceiver):
./gradlew :node-daemon:runChatListener -Pdatadir=.p2p-chat-data-listener

# Terminal 2 — point it at the listener's printed address and bundle file:
./gradlew :node-daemon:runChatSender \
    -Paddr="<listener's network address, printed by runChatListener>" \
    -Pbundlefile="<path to the listener's published-bundle.b64, printed by runChatListener>" \
    -Pmessage="Hello from M5c!" \
    -Pdatadir=.p2p-chat-data-sender
```

Confirmed, on real hardware: the sender establishes a session and sends; the listener decrypts, persists (conversation + message), prints the content and the sender's self-reported address, dials it back on a separate thread, and sends a reply, persisting that too; the sender receives the reply, persists it, and prints `M5c CONFIRMED`. Both sides' chat history is queryable afterward via `StorageService.queryMessages` against their respective `p2p-chat.sqlite`.

**Verification note.** Unlike M4a/M4b/M5a/M5b, `ChatListenerMain`/`ChatSenderMain` couldn't be compiled or run by me — they depend on `core-network`/`core-crypto` (jvm-libp2p, libsignal-client), the same sandbox limitation M0 through M4c originally had. What I did instead was read every method signature this code calls directly against the actual interface source, rather than trust memory. That caught one real bug (the `senderAddress` gap, above) before any code was written. It did not catch the other two — the wildcard-address behavior and the Netty deadlock are both things that only exist in jvm-libp2p's actual runtime behavior, not in any interface contract careful reading could inspect. **This is exactly the distinction the original version of this note drew, now demonstrated rather than just asserted:** careful hand-tracing against real interfaces is genuinely valuable and catches real bugs, but it is not a substitute for actually running the code, and this milestone is concrete proof of both halves of that claim at once.

### Deliberately not in M5c

- **No message-id dedup, no receipt sending, no read-state tracking.** `DeliveryReceiptPayload`/`ReadReceiptPayload` exist (M5b) but nothing sends one yet, and nothing checks `messageId` against history before persisting. That's M5d — deliberately kept separate rather than half-built here, so M5c stays scoped to "does the core send/receive/persist loop work."
- **No group/multi-party anything.** Still M8's territory, unchanged.
- **No interactive REPL.** Same one-shot, CLI-arg-driven, prove-the-mechanism-then-wait-for-Ctrl+C style every prior networked demo has used — a real chat UI is M7's job, not a demo Main's.
- **No general cross-machine address discovery.** `firstDialableAddress`'s loopback resolution is same-machine-only, flagged explicitly in its own Javadoc — real NAT traversal / external address discovery is still an open problem for M6/M7.

> **Amendment (M5d):** partially addressed. `DialableAddressResolver` (successor to `firstDialableAddress`, see above) now resolves same-LAN two-machine addressing correctly via real network-interface enumeration. What's still open, and still explicitly deferred to M6, is (a) NAT/different-network reachability — `ConnectionStrategy`'s existing direct-first/relay-fallback logic (M3a/M3b) is proven but not yet wired into the chat/file send paths, which still call `sendEnvelope` directly — and (b) real external/public address discovery (STUN or similar), which remains fully unbuilt.

---

## M5d — Message-id dedup + delivery/read receipt state transitions ✅ (verified on real hardware — see below)

Closes out M5: `DeliveryReceiptPayload`/`ReadReceiptPayload` (M5b) get real senders and handlers for the first time, and `saveMessage` gets a real dedup guard in front of it. Same three files touched as M5c (`StorageService`/`SqliteStorageService`, `ChatListenerMain`, `ChatSenderMain`), plus one new shared class, `core-network`'s `DialableAddressResolver` — see the M5c amendments above for why that exists and what it replaces.

**Storage layer — three new `StorageService` methods**, each solving exactly one real caller's need rather than one general-purpose "patch a message" method (matching this interface's existing convention, e.g. `markChunkReceived`):
- **`hasMessage(messageId)`** — the dedup check itself. Deliberately a query the *caller* consults before calling `saveMessage`, not a behavior change to `saveMessage` (still a plain insert). A genuine bug that reuses a `messageId` for two different messages should fail loudly with a primary-key violation, not be silently swallowed by an `INSERT OR IGNORE` — the same "fail loud on a real bug, upsert only for a legitimately-repeatable call" distinction `saveFileMetadata`/`saveConversation` vs. the plain-insert methods already draws elsewhere in this interface.
- **`updateDeliveryState(messageId, newState)`** — single-message transition. Two real callers: an inbound `DeliveryReceiptPayload` (→ `DELIVERED`), and a local "mark read" action (→ `READ`).
- **`markMessagesReadUpTo(conversationId, senderPeerId, readUpToHlcTimestamp)`** — the watermark bulk update for an inbound `ReadReceiptPayload`. **Deliberately scoped by `senderPeerId`, and worth being explicit about which direction that goes:** a read receipt reports "I've read everything up to this point," so it updates *this node's own outgoing messages* in that range — never the receipt-sender's own messages, which this node has no authority to mark on their behalf. `senderPeerId` at the real call site is always this node's own `PeerId`, not the receipt's remote sender. Relies on plain SQLite `TEXT` `<=` comparison agreeing with causal order, which is safe specifically because `HlcTimestamp#toString()`'s fixed-width zero-padded encoding makes string order match causal order exactly (see that class's own Javadoc) — not a coincidence assumed without justification.

**Daemon layer — both `ChatListenerMain` and `ChatSenderMain` now, symmetrically:**
- Check every inbound `ChatMessagePayload` against `hasMessage` before persisting. A duplicate (spec §15: "duplicate message delivery... → dedup on message_id at the storage layer before it ever reaches the UI") is still acknowledged — the sender's own earlier ack may have been lost, and re-acking is idempotent and cheap — but is not re-persisted (would violate the `messages` primary key anyway) and not re-replied-to (re-sending the chat reply text on every retransmit would be actively wrong demo behavior, not a receipt).
- Send a `DeliveryReceiptPayload` automatically for every non-duplicate message received — delivery is not a user action, unlike read.
- Accept a new optional `markread` argument (`-Pmarkread=true` on either `runChatListener` or `runChatSender`, independently). When set, the receiving side also calls `updateDeliveryState(..., READ)` locally and sends a `ReadReceiptPayload` — simulating a real "user opened the conversation" action that doesn't exist without a real UI (M7's territory), so the read-receipt round trip can be proven mechanically without one.
- All outgoing payloads a single receive event can now produce (delivery receipt, optional read receipt, and — on `ChatListenerMain`'s side — the existing M5c chat reply) are composed first, then sent from inside **one** `CompletableFuture.runAsync` block, not several independent ones, so they can't race each other for the same target connection. Still required for the identical reason M5c found: this code runs on the `OnEnvelopeMessage`/Netty event-loop thread, so `sendEnvelope` cannot be called synchronously from here.
- Handle inbound `DeliveryReceiptPayload`/`ReadReceiptPayload` — acknowledgments of a message *this* node sent earlier — by calling the two storage methods above.

**A related, pre-existing bug found while doing the addressing work bundled into this milestone (your #1), not introduced by it:** `FileSenderMain` (M4c) was reading `network.listenAddresses()[0]` raw, with no wildcard-bind handling at all — the identical gap `ChatListenerMain`/`ChatSenderMain` hit in M5c, just one that happened not to surface on the machine M4c's own real-hardware run used. Fixed the same way, via `DialableAddressResolver`.

```bash
# Same two-terminal shape as M5c. -Pmarkread=true is independent per side — set it on
# either, both, or neither.
./gradlew :node-daemon:runChatListener -Pdatadir=.p2p-chat-data-listener -Pmarkread=true

./gradlew :node-daemon:runChatSender \
    -Paddr="<listener's network address, printed by runChatListener>" \
    -Pbundlefile="<path to the listener's published-bundle.b64, printed by runChatListener>" \
    -Pmessage="Hello from M5d!" \
    -Pdatadir=.p2p-chat-data-sender \
    -Pmarkread=true

# To exercise the dedup path specifically: -Pduplicatesend=true on runChatSender (added in the
# M5e pre-M6 cleanup pass — see that section below) sends the same messageId twice automatically.
```

**Verification note — genuinely more was possible here than M5c's own note describes, worth being precise about exactly how much more.** Unlike M5c, this sandbox had a working JDK for this milestone (`javac`/`java` were not installed at the start of this session; installing `openjdk-21-jdk-headless` succeeded once `apt-get update` was run first — worth remembering for next time rather than concluding the sandbox can't compile Java at all). That changed what "verification" could mean here:
- **`DialableAddressResolver`** — pure JDK, zero dependencies. Compiled and run directly: 7/7 checks pass, including the exact wildcard-bind case M5c found on real hardware, and confirmed graceful fallback to loopback in an environment with no real LAN interface (this sandbox).
- **The three `StorageService`/`SqliteStorageService` methods** — `core-storage`'s only external dependency, `sqlite-jdbc`, isn't on Maven Central specifically, just hosted somewhere reachable — same reasoning M4d's own verification note used. Fetched the same jar version `build.gradle.kts` pins from its GitHub release, compiled the real `core-model`+`core-storage` source (with these three new methods) against it, and ran a standalone harness against a **real SQLite database using the real migration schema** — not mocked. 10/10 checks pass, including the sender-scoping and watermark-boundary edge cases (a message exactly *at* the watermark is marked read; one *after* it is not; the counterparty's own messages are untouched by a watermark applied to this node).
- **`ChatListenerMain`/`ChatSenderMain`** — still genuinely blocked on jvm-libp2p/libsignal-client, which remain unreachable (Maven Central/Cloudsmith/JitPack are not on this sandbox's allowlist). But rather than hand-tracing against interface source alone (M5c's approach), minimal stub classes were written matching the *exact real public signatures* of the handful of libsignal-client leaf types these files touch (`SignalProtocolAddress`, `PreKeyBundle`, `InMemorySignalProtocolStore`, plus stub bodies for `core-crypto`'s own public classes), and both edited daemon files were compiled — for real, by `javac`, not read by eye — against the **real** `core-model`, `core-messaging`, `core-network` (including the real `DialableAddressResolver` and `PeerNetworkService`), `core-storage`, and `core-identity` source. Zero errors. This catches real classes of mistake M5c's pure hand-tracing couldn't have (wrong method signatures, wrong types passed, a typo'd field name, an incorrectly-matched sealed-type branch) — genuinely stronger than M5c's own verification, even though it stops short of what only real jvm-libp2p/libsignal-client execution can catch (exactly the two bugs M5c's own hardware run found, which no amount of interface-reading or stub-compiling could have). **That gap is real and this section says so plainly, rather than letting "it compiled cleanly" imply more than it does.**

> **Amendment — confirmed on real hardware.** The gap above is closed: a real two-terminal run (`-Pmarkread=true` both sides, `-Pduplicatesend=true` on the sender — see M5e below for that flag) confirmed every piece of M5d actually works end to end, not just compiles. The listener's console showed exactly one `[chat] duplicate message ... ignored (already persisted) — re-acking anyway` line for the two envelopes the sender's `-Pduplicatesend` produced, and exactly one auto-reply went out — not two. Both delivery receipts and both read-receipt watermarks arrived and correctly updated `DeliveryState` on each side (the sender legitimately saw *two* `DELIVERED` log lines for the same `messageId` — the real ack, plus the idempotent re-ack for the duplicate — which is correct, not a bug: see M5e's own note on this). The watermark's `senderPeerId` scoping was independently traced through the real hex peer IDs in the transcript and confirmed correct in both directions.

### Deliberately not in M5d

- **No real UI-driven read state.** `markread` is a CLI flag simulating a user action that doesn't exist yet without one — still M7's job.
- **No retry/resend on a lost delivery.** A `DeliveryReceiptPayload` that never arrives (network drop, process killed) is not detected or retried anywhere — `DeliveryState` can sit at `SENT` indefinitely. Real reliability (timeouts, retries, an explicit "failed to deliver" surface) is unbuilt.
- **No relay-fallback wiring in the chat/file send paths.** Flagged above and in the M5c amendments — `ConnectionStrategy` (M3a/M3b) exists and is proven, but `sendEnvelope` is still called directly everywhere in `node-daemon`. Explicitly deferred to M6, per your own call on how to sequence this.
- **No group/multi-party anything.** Still M8's territory, unchanged.

---

## M5e — Pre-M6 cleanup pass ✅ (verified on real hardware)

A peer review produced a 17-item pre-M6 checklist — build/environment fixes, a real file-transfer bug, decoder hardening, an HLC trust-boundary gap, and a batch of M6 design questions correctly identified as too early to answer yet. Reviewed against the actual source (not taken on faith) before doing any of it; four items turned out to be genuine, checkable bugs, done here as Batches 1–3. The remaining items were deliberately **not** done in this pass — see "Open M6 design decisions" below — since folding them in risks turning a cleanup pass into M6 itself.

**File-transfer deadlock fix.** `FileReceiverMain.handleOffer` and `FileSenderMain`'s chunk-response handler were both calling `network.sendEnvelope(...)` synchronously from inside their `OnEnvelopeMessage` callback — the identical Netty-event-loop deadlock hazard M5c found and fixed in chat, just never backported, since M4c predates that discovery. Fixed the same way: compose the encrypted frame(s) first, send from inside one `CompletableFuture.runAsync` block. `FileSenderMain`'s case can send several chunks per callback invocation, so all of them are composed up front and sent sequentially from a single async block, not one per chunk, so they can't race each other and arrive out of order.

**Decoder/marker validation, audited across all 6 codecs in the project, not just the 2 originally flagged.** `EncryptedFrameCodec` and `RelayFrameCodec` had the actual bug — `decode` treated any marker byte other than the one expected "true" value as the other defined value, no `else`/`default` branch at all, so a corrupted or malformed marker silently produced a wrong-but-plausible decode instead of a loud failure. `DiscoveryFrameCodec`/`FileTransferMessageCodec`/`ChatMessageCodec` already validated markers correctly; all three, plus `RelayFrameCodec`/`DiscoveryFrameCodec`, had no bounds check on any length-prefixed field before allocating — `new byte[length]` on an adversarial or corrupted length throws `NegativeArraySizeException` on a negative value or attempts an unbounded allocation on a huge one. Security-relevant specifically for `RelayFrameCodec`/`DiscoveryFrameCodec`, since `relay-server` runs this decode against bytes from arbitrary connecting peers, not just this project's own two ends of a session. `PreKeyBundleCodec` — not originally named, found by the identical pattern while auditing the rest — got the identical one-line fix.

**HLC remote clock-drift guard.** `HybridLogicalClock`'s own Javadoc had flagged this as a deliberately deferred gap since M5a: nothing bounded how far in the future a remote peer's timestamp could claim to be, and `update`'s `max()`-based algorithm would just adopt it, potentially wedging the local clock there for a long time. `HybridLogicalClock.checkDrift(HlcTimestamp, Duration)` — a new, strictly additive, opt-in method — is the fix; `update` itself is completely unchanged, so every test written against it before this pass remains valid unchanged. Wired into both `ChatListenerMain` and `ChatSenderMain`, checked before `update` ever sees a remote timestamp, ahead of even the dedup lookup — reject-early. Default bound is 5 minutes, documented as a policy choice, not a rigorously derived constant.

**M5d dedup, exercised live for the first time.** A new, permanent `-Pduplicatesend=true` flag on `runChatSender` resends the same `messageId` a second time — freshly re-encrypted through the Signal ratchet each time, since a byte-identical resend isn't how a real retransmission would look anyway (the Double Ratchet advances on every `encrypt()` call by design) — specifically to exercise the listener's dedup path over the real wire, not just the isolated `hasMessage` unit test.

```bash
./gradlew :node-daemon:runChatListener -Pdatadir=.p2p-chat-data-listener -Pmarkread=true

./gradlew :node-daemon:runChatSender \
    -Paddr="<listener's network address>" \
    -Pbundlefile="<listener's published-bundle.b64>" \
    -Pmessage="Hello from M5e!" \
    -Pmarkread=true \
    -Pduplicatesend=true \
    -Pdatadir=.p2p-chat-data-sender
```

**M5e CONFIRMED on real hardware, in two separate live runs:**
- **Chat dedup + receipts** — the listener's console showed exactly one `duplicate message ... ignored (already persisted) — re-acking anyway` line for the two envelopes `-Pduplicatesend` produced, and exactly one auto-reply went out. The sender legitimately logged `DELIVERED` twice for the same `messageId` — the real ack, plus the idempotent re-ack for the duplicate delivery — which is correct, not a double-count bug: two envelopes genuinely arrived, one was new, one was a dedup hit, and both get acknowledged.
- **File transfer** — a real `runFileSender`/`runFileReceiver` two-terminal run, 13 chunks of a 198-byte file at a 16-byte chunk size, sent and received strictly in order (0→12 both sides — not guaranteed by the transport, only by composing all chunks before sending any of them), no hang, SHA-256 matched exactly on both ends.
- **`./gradlew test`** — full suite, all 9 modules, `BUILD SUCCESSFUL`. This is also the first time every JUnit test file written across M5d/M5e ran against the *real* JUnit 5/AssertJ (not the hand-built stub API shapes used to compile-check them in the sandbox that produced this code) — including every malformed-input/unknown-marker codec test and all 7 new `HybridLogicalClock.checkDrift` tests.
- Every M0–M5b/M4a/M4b/M4d standalone demo re-run and produced its original documented pass count unchanged (20/20 HLC, 23/23 chat codec, 22/22 file-transfer codec, 7/7 chunk-resume) — confirms nothing in this pass disturbed an earlier milestone.

**Checked and found not to be an issue, worth recording so it isn't re-investigated later:** the checklist also flagged possible stale comments claiming `OnEnvelopeMessage` exposes a raw jvm-libp2p `PeerId`. Grepped for every reference to `io.libp2p.core.PeerId` across the codebase and docs — every one found is correctly past-tense/historical (M3d's own retrospective documentation) or, in `EnvelopeProtocol`'s case, an accurate description of the exact line where the conversion to this project's own `PeerId` happens. No fix needed.

**Deliberately not done in this pass — left for M6, see below.**

---

## M6a — Shared decrypted-message dispatch 🔄 (sandbox-verified — pending confirmation on real hardware)

First piece of M6, and the smallest: the "shared decrypted-message dispatch" item under "Open M6 design decisions" below, now resolved. `core-messaging` and `core-filetransfer` keep their own independent sealed hierarchies exactly as before — `ChatWireMessage`'s own Javadoc had already considered and deferred unifying them (§6) — this doesn't merge them, it wraps them from the outside. New `ApplicationMessageRouter` (`node-daemon`) peeks the one marker byte every decrypted payload already carries and delegates to whichever of `ChatMessageCodec`/`FileTransferMessageCodec` owns it; a new `DispatchedMessage` sealed wrapper (`Chat`/`FileTransfer` records) gives callers one exhaustively-matchable return type without either core module knowing this type exists. Chat's markers (`{2,3,4}`) and file transfer's (`{6,7,8}`) were already disjoint — confirmed by reading both codecs' actual marker constants before writing anything — so no new outer envelope was needed. Markers `0`/`1` (handshake) are treated as reaching the router in error, not routed — PQXDH session establishment already happens transparently inside `SecureSessionService.decrypt()`, one layer below; `5`/`9` (`GROUP_OP`/`PRESENCE_PING`) are rejected as reserved-not-unknown, with a message saying so.

Pure JDK — `node-daemon`'s dependency on `core-messaging`/`core-filetransfer` was already there for the demo Mains, no new dependency added. Compiled and run directly in the sandbox that produced this code (`core-model`+`core-messaging`+`core-filetransfer` have zero external dependencies), same as M4a/M4b — not hand-traced. A real JUnit 5 test suite (`ApplicationMessageRouterTest`) exists alongside a throwaway, non-JUnit harness — used only because JUnit itself isn't resolvable in this sandbox — exercising the same 15 scenarios; the JUnit version stub-compiles cleanly against the real production classes, but only the throwaway harness has actually *executed*, 15/15. Two real bugs surfaced only by running it, not by reading the code: `ChatMessagePayload.messageId` requires an actual UUID (a placeholder like `"msg-1"` throws immediately), and both `ChatMessagePayload`/`FileOfferPayload` have `byte[]` fields, which Java records compare by reference in their generated `equals()` — a naive `assertThat(decoded).isEqualTo(original)` would have passed on a broken decode and failed on a correct one.

---

## M6e-1 — Persistent Signal Protocol session store 🔄 (sandbox-verified against real SQLite — pending confirmation against the real libsignal-client jar)

Every demo Main through M5e uses libsignal's own `InMemorySignalProtocolStore` — correct for a proof, wrong for a daemon: sessions, one-time prekeys, and trusted remote identities all evaporate on restart, forcing a fresh PQXDH handshake per peer per restart and defeating the actual point of the Double Ratchet's forward secrecy. `LibsignalSecureSessionService`'s constructor already took the `SignalProtocolStore` *interface*, not the concrete in-memory class — this needed zero changes to `core-crypto`.

New `SqliteSignalProtocolStore` (`node-daemon`) implements the full SPI — `IdentityKeyStore`/`PreKeyStore`/`SignedPreKeyStore`/`KyberPreKeyStore`/`SessionStore` for real; `SenderKeyStore`'s two group-messaging methods throw `UnsupportedOperationException` deliberately (M8's scope, and the one sub-interface nothing in this project has touched yet to hand-trace a signature against). New migration `V002__signal_store.sql` (§9 of `architecture-spec.md`), five tables, every record treated as an opaque blob — `.serialize()` in, the matching `byte[]` constructor out, never inspected — per Signal's own guidance for store implementations. One deliberate schema departure worth flagging: Kyber pre-keys get a `used` flag, not a `DELETE`, unlike one-time EC pre-keys (`removePreKey`) — real PQXDH Kyber pre-keys are last-resort/reusable, not strictly single-use, and `markKyberPreKeyUsed` is a genuinely different SPI method from `removePreKey` for exactly that reason. `saveIdentity`/`isTrustedIdentity` implement real trust-on-first-use plus MITM-key-swap detection, not a placeholder — connects directly to §15's existing "key compromise" bullet, whose safety-number-reverification flow has nothing to hook into without this. New `SynchronizedSignalProtocolStore`, a generic decorator (not baked into the SQLite class itself) serializing every SPI call on one monitor — the first time this project has needed real concurrent-session thread-safety, since every demo Main through M5e only ever held one session at a time.

Compiled against hand-traced stub types matching every already-compiling libsignal usage elsewhere in this project — this sandbox can't resolve `org.signal:libsignal-client` any more than it could `jvm-libp2p`, same limitation as M2a–M5e's crypto/network pieces. Unlike those, though, the JDBC/SQL half of this class doesn't need the real libsignal jar to test for real: fetched the real `sqlite-jdbc` driver from GitHub Releases (same route M4d used) and ran the whole store against a real SQLite database, including a genuine restart (one `SqliteDatabase` closed, a brand-new one opened against the same directory). 18/18 checks passed, after fixing two real bugs the direct run caught that reading the code alone would not have: a stray semicolon used as ordinary prose punctuation inside a `V002` comment, which broke `MigrationRunner`'s own already-documented naive `split(";")` statement splitter (fixed in the migration file, not in `MigrationRunner`, which wasn't wrong by its own stated contract); and a hand-traced `SignalProtocolAddress` stub whose constructor didn't actually store its arguments, silently returning `""`/`0` from every `getName()`/`getDeviceId()` call — a bug in the sandbox's simulation of the real type, not in `SqliteSignalProtocolStore` itself, confirmed by tracing a failing assertion back to a raw SQL row showing address `.0` instead of the real peer ID. Also ran 16 threads × 50 operations against `SynchronizedSignalProtocolStore` concurrently — zero errors.

What real execution couldn't reach: the actual libsignal-client serialization semantics — whether `SessionRecord.serialize()` round-trips a *real* Double Ratchet's internal state correctly, not just this sandbox's faithful-but-fake stub bytes. That gap only closes on real hardware, next.

M6e-1 deliberately doesn't wire this store into any live session — that's M6e-2, not yet done, and this node's own local identity (`IdentityKeyPair` + registration ID) still comes from `SignalIdentityVault` (M2a) exactly as before. Built and proven ahead of M6b–M6d despite the letter ordering — a deliberate reordering, since this is the highest-blast-radius piece in the whole M6 roadmap and front-loading it was judged better than building three more layers on top first and finding out later.

**Revision after a real build against `libsignal-client` 0.94.0:** two real signature mismatches surfaced, both fixed. `saveIdentity` returns `IdentityChange` (`NEW_OR_UNCHANGED`/`REPLACED_EXISTING`), not `boolean` — same semantics as before, clearer type. `markKyberPreKeyUsed` takes `(kyberPreKeyId, signedPreKeyId, ECPublicKey baseKey)` and can throw `ReusedBaseKeyException` — real base-key replay protection, not a bare consumption flag. Fixing the second one caught a real bug in the first draft's implementation, not just its signature: base-key tracking had gone into a plain in-memory `Map`, silently losing that protection on every restart — the exact problem this whole class exists to solve, reappearing inside itself. New migration `V003__kyber_base_key_replay.sql` (a composite-key SQLite table, not an edit to the already-shipped `V002`, per §9's own rule) fixes it, and does the byte-value comparison for free via `PRIMARY KEY` uniqueness — sidestepping a second latent bug the in-memory version had: `Set<ECPublicKey>` relies on `ECPublicKey` having value-based `equals()`/`hashCode()`, which nothing confirms it does. Re-verified the same way as the original M6e-1 pass — real `sqlite-jdbc` against a real SQLite database, including a restart — with a test built specifically to catch the identity-based-equals failure mode: two separately-constructed `ECPublicKey` instances carrying identical bytes, not the same object reused. 24/24 real checks pass (the original 18 plus 6 new, all against the corrected signatures). A follow-up real build against the actual jar caught two more real issues in the test fixtures themselves, not the production code: `SessionRecord`'s real constructor parses straight into a protobuf `SessionStructure`, so an arbitrary byte literal like `{7,7,7,7}` fails tag validation outright — fixed by generating a real, fully-populated session via an actual PQXDH handshake instead, with a `length > 50` assertion specifically guarding against a regression silently falling through to the empty-session fallback; and a second, previously unexercised `IdentityKey(ECPublicKey)` constructor overload. Confirmed complete: 39/39 tests passing on a fresh, live run against the real jar, real SQLite, and real crypto — not a cached Gradle result.

---

## M6b — One outbound send path 🔄 (real production code compiled and executed directly — no jvm-libp2p stubs needed for this one)

The "one outbound send path" item under "Open M6 design decisions" below, now resolved — and narrower than that bullet implied once actually researched: `ConnectionStrategy` (M3b) already does everything the decision logic needs — direct-first, relay fallback, always resolving to a `ConnectivityStatus` (`DIRECT`/`RELAYED`/`UNREACHABLE`) rather than throwing. What's actually missing, confirmed by reading every M5c–M5e demo Main's real send call before writing anything: **none of them call `ConnectionStrategy` at all** — every one bypasses it with a raw, synchronous `network.sendEnvelope(target, data)`, the 2-arg overload with no timeout. `DialableAddressResolver`'s own Javadoc already said as much directly: "the existing direct-first/relay-fallback path... actually wired into a caller that currently bypasses it... Tracked as M6 work, not attempted here." M6b is that caller.

New `OutboundMessageService` (`node-daemon`) wraps `ConnectionStrategy.send(...)` in `CompletableFuture.supplyAsync(...)` on its own dedicated, bounded thread pool — not `ForkJoinPool.commonPool()`, which every M5c-e demo Main's ad hoc fix defaulted to, harmless for a one-shot process but the wrong default for a long-running daemon sharing a JVM with whatever else runs in it. Adds an overall timeout on top of `ConnectionStrategy`'s own direct-only one, since its relay attempt has no timeout at all — confirmed by reading that source, not assumed. Any failure, including this class's own timeout, maps back to `UNREACHABLE` rather than a failed future, extending `ConnectionStrategy`'s own stated principle ("always returns a status rather than sometimes throwing") one layer up rather than letting it stop at that class's boundary.

Genuinely different verification story from M6a/M6e-1: `ConnectionStrategy`, `ConnectivityStatus`, and `PeerNetworkService` have zero jvm-libp2p or Netty imports — confirmed by grepping every file in `core-network` before writing anything, not assumed. That meant a real `PeerNetworkService` test double (no fake crypto, no stub types) was enough to get **real, executed coverage of real production code** — `OutboundMessageService` and `ConnectionStrategy` both compiled and run directly in this sandbox, nothing hand-traced. 9 real scenarios, including reproducing the actual M5c-e deadlock directly (a hung underlying call, proving `send()` returns near-instantly while the future resolves later) and concurrent sends to different peers not blocking each other. First run: 8/9 passed, one real bug caught by the ninth — `CompletableFuture.supplyAsync(...)` calls `executor.execute(...)` synchronously as part of setting up the async stage, so a call on an already-closed executor throws `RejectedExecutionException` straight out of `send()` itself, before any future exists for the `.exceptionally(...)` chain to catch it. Fixed with a try/catch around the whole call; re-run: 9/9.

**What this deliberately doesn't do**, so it isn't mistaken for more than it is: no relay address book or per-peer relay preference (`send` takes `relayMultiaddr` as a parameter, same as `ConnectionStrategy` already does — where that value comes from is M6e-2/M6f's decision); no retry policy beyond `ConnectionStrategy`'s existing one-direct-one-relay contract; and it isn't wired into any demo Main or the (not-yet-built) session manager — that's M6e-2's job once it exists.

---

## Open M6 design decisions

Captured explicitly here, not resolved — a short agenda for M6's own first design pass, so none of it gets silently lost or rediscovered from scratch. **Updated after M6f** to mark resolved items and add newly-surfaced gaps from the M6g gap analysis — see [`docs/M6g-gap-analysis-and-plan.md`](docs/M6g-gap-analysis-and-plan.md) for the full analysis.

- ~~**Canonical runtime peer identity.**~~ **Resolved in M6e-2** (see below) — the libp2p peer ID, everywhere: `SessionManager` builds every `SignalProtocolAddress` from the `PeerId` `OnEnvelopeMessage` itself delivers, confirmed (by reading every construction site in `core-network`) to always be the real libp2p base58 ID, never the app-identity hex ID. The third value this bullet warned about — `ChatListenerMain`/`ChatSenderMain`'s own `identity.peerId()` for their local `SignalProtocolAddress` — was checked directly, not assumed: almost certainly harmless there, since a local address is an internal label libsignal never validates against anything a remote party sends, but `SessionManager` doesn't repeat it regardless, for consistency with every other identity use in this project.
- ~~**Shared decrypted-message dispatch.**~~ **Resolved in M6a** (see above) — `ApplicationMessageRouter` + `DispatchedMessage`, a thin marker-byte router in `node-daemon`, not a shared envelope or a merge of the two existing hierarchies.
- ~~**One outbound send path.**~~ **Resolved in M6b** (see above) — `OutboundMessageService`, wrapping the already-complete `ConnectionStrategy` for async execution off Netty callback threads plus an overall timeout, not new decision logic.
- ~~**Discovery record contents.**~~ **Resolved in M6f** — `DiscoveryRecord` carries addresses, optional pre-key bundle, optional relay multiaddr, and expiry timestamp. `SignedDiscoveryRecord` wraps it with Ed25519 signature and public key. Client-side verification via `DiscoveryRecordCodec.verifyAndDecode(...)` checks signature, derived peer ID match, and expiry — the actual MITM defense. Relay does best-effort expiry peek only (`decodeUnverified`), deliberately not the trust boundary.
- **Pre-key bundle lifecycle.** Demo listeners publish a fresh bundle on every restart. **Partially addressed by M6e-1** — the *persistence mechanism* now exists (prekeys survive a restart, one-time prekeys are physically deleted on use, Kyber prekeys are marked-used rather than deleted) — but the *generation policy* (when bundles are regenerated, how many one-time prekeys to keep in reserve, whether/how signed prekeys rotate) is still undecided. **Concrete instance found during M6e-2 verification**: `SessionManagerListenerMain` publishes one bundle to a file once, at startup, and never republishes — a second sender reading that same file after the first has already consumed its one-time prekey gets a real `InvalidKeyIdException`, correctly rejected, not a `SessionManager` bug. Deliberately not fixed in the demo — deferred to M6f, since discovery is the actual mechanism that replaces "hand someone a static bundle file" at all. **M6f confirmed the record format supports republication** (`publish()` is a plain overwrite), but nothing yet decides *when* to call it. **Deferred to M6h** — needs a daemon loop to run a schedule from, which doesn't exist until the composition root.
- ~~**Storage transaction boundaries.**~~ **Resolved in M6e-2** (see below) — the consolidated receive path (save/upsert conversation, save message) is wrapped in `StorageService.runInTransaction`; the dedup check itself stays outside that transaction deliberately, made safe by a single-threaded inbound executor rather than a database-level lock, closing the same race a naive "check then insert across two separate calls" would otherwise have under concurrent delivery.
- ~~**Daemon-facing error vocabulary.**~~ **Resolved in M6g gap analysis** — `DaemonErrorCode` enum designed in `docs/M6g-gap-analysis-and-plan.md §3` (M6g-4): `PEER_UNREACHABLE`, `RELAY_UNAVAILABLE`, `MALFORMED_RECORD`, `CRYPTO_FAILURE`, `DUPLICATE_MESSAGE`, `STORAGE_FAILURE`, `INVALID_REQUEST`, `METHOD_NOT_FOUND`, `UNKNOWN_CONVERSATION`, `UNKNOWN_CONTACT`. Implementation deferred to M6g-4.
- **Principle, not a task:** treat every `node-daemon` demo `Main` as a proven mechanism, not final architecture. Their static-helper duplication, `System.out`-as-state-reporting, and one-shot-process assumptions were all correct choices for what they were proving — not patterns to carry into the real daemon.

**Newly identified gaps (post-M6f gap analysis):**

- **Invite code format.** §7's `contacts.add({ inviteCode })` was never defined anywhere. **Resolved in M6g gap analysis** — base64url-encoded JSON containing peer ID, optional discovery/relay server multiaddr, and optional display name. Does *not* contain a pre-key bundle (that's what signed discovery records are for). See `docs/M6g-gap-analysis-and-plan.md §2.1`.
- **Peer routing / address book.** `messages.send` in §7 takes a `conversationId`, but `SessionManager.sendChatMessage()` needs raw multiaddrs and optionally a `PreKeyBundle` — today hand-carried as CLI args. **Resolved in M6g gap analysis** — `PeerRoutingTable` class in `node-daemon`, backed by a new `V004__peer_routes.sql` migration. See `docs/M6g-gap-analysis-and-plan.md §2.3`. Scoped to M6g-2.
- **`StorageService` read-side queries.** `StorageService` has `saveContact`/`saveConversation`/`saveMessage` but no `listContacts()`/`listConversations()`/`getConversation()`/`getContact()`. Every `*.list` method in §7 needs backend queries that don't exist. **Scoped to M6g-1.**
- **Event emission from `SessionManager` to WebSocket layer.** `SessionManager` persists messages and sends receipts, but has no callback mechanism to notify the WebSocket/JSON-RPC layer. §7's push events (`event.message.received`, `event.transfer.progress`, `event.network.statusChanged`) need this. **Resolved in M6g gap analysis** — `DaemonEventListener` interface. See `docs/M6g-gap-analysis-and-plan.md §2.5`. Scoped to M6g-3.
- **File-transfer integration into `SessionManager`.** `FileTransferHandler` (M6e-2) is a no-op interface. The proven M4a–M4d chunk logic needs to be consolidated into a real `DefaultFileTransferHandler`, with `sendFile()`/`acceptFileTransfer()` methods on `SessionManager`. `files.cancel` deferred to M7. **Scoped to M6g-3.**
- **`conversations.createGroup` and `files.cancel`.** Both are in §7 but explicitly deferred — `conversations.createGroup` to M8 (CRDT + sender-key), `files.cancel` to M7 (needs UI-driven interruption). Both will return `METHOD_NOT_FOUND` with "available in a future version" until then.

---

## M6c — Hand-rolled JSON value model ✅ (fully confirmed — pure JDK, compiled and run directly, nothing hand-traced)

A sealed `JsonValue` hierarchy (`JsonObject`/`JsonArray`/`JsonString`/`JsonNumber`/`JsonBoolean`/`JsonNull`, `node-daemon`) plus `JsonCodec` — `parse(String)`/`write(JsonValue)`, one class, matching the same combined-encode-decode convention every other codec in this project already uses (`ChatMessageCodec`, `FileTransferMessageCodec`, `RelayFrameCodec`) rather than splitting into separate parser/writer classes. Zero dependency, hand-rolled, same call made for the whole project back when M6 was first planned — reconfirmed independently before writing this, not just assumed still right.

**Scope boundary, deliberate:** this is the generic JSON layer only — no `Request`/`Response`/`Notification` JSON-RPC envelope types here. Those are M6g's job, built on top of this, once the error-vocabulary decision below is actually settled rather than guessed at early.

**Real pitfalls a hand-rolled JSON parser has a long history of getting wrong, addressed deliberately:**
- **Number precision.** `JsonNumber` stores the validated raw text, not an eagerly-parsed `double` — a `double` cannot exactly represent every `long` beyond 2^53, and this project's ids/file-sizes/chunk-counts are exactly the kind of values that would silently corrupt. Typed accessors (`asLong()`, `asInt()`, `asDouble()`, `asBigDecimal()`) parse on demand instead, each failing loudly rather than truncating. Proven with an actual 2^53+1 value round-tripping exactly.
- **`\uXXXX` surrogate pairs.** The common bug is manually recombining a pair into a single codepoint. Since Java strings are already UTF-16 — the same representation the escapes describe — decoding each one as a single `char` and appending both in sequence reproduces the pair correctly without needing to combine anything by hand. Proven with a real supplementary-plane character (U+1F600, outside the BMP).
- **Strict number grammar** — rejects leading zeros (`01`), a bare decimal point (`.5`, `5.`), and a digit-less exponent, all real JSON invalidity a sloppy parser would let through.
- **Unescaped control characters inside strings** rejected on read, per RFC 8259 §7.
- **Max nesting depth (32)**, not an arbitrary round number — sized to the real payload shapes `architecture-spec.md §7` actually shows (1–2 levels; even a 500-element array of messages is still only 1–2 levels of nesting, not 500), tight enough to meaningfully bound a malicious deeply-nested payload once this parses untrusted input over a socket (M6d), without needing to guess a number.
- **Insertion order preserved**, both from parsed input and programmatic construction — caught one real bug here: the first draft's defensive immutable copy used `Map.copyOf(...)`, whose own documentation does not guarantee preserving iteration order (and whose real implementation doesn't) — silently undoing the entire reason `LinkedHashMap` was chosen. Caught by actually running the insertion-order test, not by reading the line; fixed with an order-preserving copy instead.

Narrowing convenience accessors (`asString()`, `asLong()`, `asObject()`, etc.) live as default methods directly on `JsonValue` — strict, not coercive: calling `asLong()` on a `JsonString` throws rather than attempting to parse the string as a number, so a real schema mismatch fails clearly at the point of the mistake rather than producing a silently wrong value later.

Genuinely the cleanest verification story of anything in M6 so far: zero external dependency of any kind, so nothing here is hand-traced or stub-compiled against anything. 36/36 real, executed checks — number precision, surrogate pairs, every malformed-input path, the depth guard, and both bugs this milestone actually caught by running it.

---

## M6d — WebSocket transport ✅ (fully confirmed — real build, real dependency fix, real fresh test run, all four scenarios passing)

`ws://127.0.0.1:<port>/v1` — the transport `architecture-spec.md §7` describes the Electron app connecting to. New `DaemonWebSocketServer`/`DaemonWebSocketFrameHandler`/`WebSocketSession`/`WebSocketTextHandler` (`node-daemon`), built on Netty's `WebSocketServerProtocolHandler` — a deliberate decision, not a default, and revisited explicitly before building: `jvm-libp2p` already pulls Netty transitively (confirmed by resolving the real dependency tree, including `netty-codec-http`, and pinning the exact version explicitly rather than trusting transitive resolution to stay put), so this adds no new dependency. Hand-rolling RFC 6455 framing — client-frame masking, fragmentation, the close handshake — was the one place in this whole project where "our own tests pass" would have been a meaningfully weaker guarantee than everywhere else, since it can't prove a real browser's native `WebSocket` implementation will actually accept what was built. `WebSocketServerProtocolHandler` is the already-interoperable implementation browsers talk to correctly.

Researched properly before writing anything — pulled Netty's actual tagged source (`netty-4.1`/`netty-4.2` branches) rather than working from memory or general Netty familiarity, since the exact version matters and older docs turned out to disagree with current API in at least one place worth calling out: `TextWebSocketFrame.getText()` is the old (pre-4.1) accessor; `TextWebSocketFrame.text()` is current — confirmed against multiple independent 4.1-era sources before use, not assumed. Also confirmed directly, not assumed: the real pipeline order (`HttpServerCodec` → `HttpObjectAggregator` → `WebSocketServerProtocolHandler` → this project's own frame handler, matching Netty's own official example almost exactly); that ping/pong/close control frames never reach this project's own code at all, handled entirely upstream by `WebSocketServerProtocolHandler` itself (confirmed via its own Javadoc — a large part of the actual case for using it); and that `allowExtensions` should be `false`, not the `true` Netty's own example uses — that example also wires in `WebSocketServerCompressionHandler` for `permessage-deflate`, and advertising extension support without actually implementing it would be a real protocol mismatch. permessage-deflate was already out of scope when M6 was first planned; this is that decision being honored, not revisited.

A dedicated `EventLoopGroup` pair, entirely separate from whatever `jvm-libp2p` manages internally for `PeerNetworkService` — two independent Netty-based subsystems in one process, not one shared implicitly (and `PeerNetworkService` exposes no hook to share one regardless).

**Honest verification status when this was first built — the weakest of any M6 piece at the time:** no Netty jar was reachable in this sandbox any more than `libsignal-client` was for M6e-1 — same limitation, confirmed the same way (checked GitHub Releases for jar assets, found none). Unlike M6e-1, there was no "real SQLite, fake crypto" middle ground available — nothing in this class could be exercised for real without Netty itself. Everything stub-compiled cleanly against hand-traced type shapes built from real, version-matched Netty source, including a real JUnit test (`DaemonWebSocketFrameHandlerTest`) written against Netty's own officially-recommended `EmbeddedChannel` testing utility — deliberately scoped to this project's own glue logic (session registration, message forwarding, disconnect cleanup) rather than attempting to re-prove `WebSocketServerProtocolHandler`'s handshake/framing correctness, which is Netty's job and already covered by Netty's own test suite.

**Closed on the real build.** `node-daemon/build.gradle.kts` had no Netty dependency at all — flagged before the real build was attempted, and confirmed necessary: without an explicit `implementation("io.netty:netty-codec-http:4.2.10.Final")`, `:node-daemon:compileJava` failed outright. One line fixed it — and, usefully, `netty-codec-http` alone was sufficient, no separate `netty-transport` addition needed. That's not a coincidence: Gradle's `implementation`/`api` distinction is per-hop, not transitively opaque all the way down — whatever `netty-codec-http` itself declares as `api` in its own module metadata (almost certainly `netty-transport`/`netty-buffer`/`netty-common`/`netty-handler`, since `HttpServerCodec` etc. are themselves `ChannelHandler`s exposing those types in their own signatures) becomes visible to `node-daemon` at compile time regardless of what scope `node-daemon` uses to depend on `netty-codec-http` itself. All 4 `DaemonWebSocketFrameHandlerTest` scenarios passed on a real, fresh (`--rerun-tasks`) run — 88/88 across all of `node-daemon`, whole-repository build clean.

---

## M6e-2 — SessionManager: the multi-session daemon core 🔄 (the testable seam — dispatch/dedup/persistence — fully confirmed by real execution against real SQLite; the network+crypto wiring stub-compiles against confirmed real signatures, same limitation as every jvm-libp2p+libsignal-touching piece of M6)

The actual heart of M6 — everything M6a, M6b, and M6e-1 were built to be wired into. New `SessionManager` (`node-daemon`) replaces M5c/M5e's one-shot, single-peer, hardcoded-remote demo shape with a real long-running core: one listener, any number of concurrent peer sessions, each fully isolated.

**Scope, stated explicitly, same discipline as everywhere else in M6:**
- **In scope:** the full inbound pipeline (decrypt → `ApplicationMessageRouter` dispatch → persist, with real dedup and the storage-transaction-boundaries decision from the list above finally resolved) for chat traffic, and outbound chat sends wrapping `OutboundMessageService`. Both buildable now, no dependency on M6f/M6g — PQXDH session establishment happens transparently on the receiving side of a PreKey message (confirmed by re-reading `LibsignalSecureSessionService`'s real contract, not assumed), so nothing here needs discovery or an RPC surface to exist first.
- **Explicitly deferred, not silently skipped:** relay-delivered *inbound* reception (nothing through M5e or M6b proved this either — genuinely new networking capability, not wiring); the full resumable file-transfer chunk state machine (routed correctly, proving M6a's dispatcher for both message families, but delegated to a pluggable `FileTransferHandler` rather than reimplemented inline — the same pattern `WebSocketTextHandler` already established); pre-key bundle lifecycle/replenishment policy (still the same open item it's been since M6e-1 — a session manager wiring the store in isn't the same job as managing the store's contents).

**Canonical identity, actually fixed this time.** Every `SignalProtocolAddress` here comes from the `PeerId` `OnEnvelopeMessage` itself delivers — the real libp2p base58 ID, confirmed by reading every construction site in `core-network`, never the app-identity hex ID `ChatListenerMain`/`ChatSenderMain` used for their own local address. Checked what that actually meant rather than assuming it was simply wrong: almost certainly harmless there specifically, since a local `SignalProtocolAddress` is an internal label libsignal never validates against anything a remote party sends — `SessionManager` still doesn't repeat it, for consistency with every other identity use in this project (conversation IDs, storage's `sender_peer_id`, remote addresses), not because M5c was proven broken.

**Device id is `1`, not `0`.** Checked against every real `SignalProtocolAddress` construction site in this project — 20+, zero exceptions — before writing anything, catching a real mistake in the first draft (which had reasoned by analogy to the unrelated storage-layer `DeviceId.DEFAULT` value of `"0"`, a completely different numbering space) before it became actual shipped code.

**One `LibsignalSecureSessionService` instance, not a per-peer map.** Its real methods all take the remote address as a parameter rather than baking it into the object — confirmed by re-reading the class, not assumed — so one instance, constructed once this node's own peer ID is known, correctly serves every remote peer; the store is what's keyed per-address, not this service.

**A single-threaded inbound executor, deliberately, not a pool.** Two reasons: the same "correctness over throughput" reasoning `OutboundMessageService` already established, and — concretely — it closes a real race a pool would have left open: two near-simultaneous deliveries of the same `messageId` both passing a dedup check before either's insert commits, the second then failing on `messages`' own primary key. Strict sequential processing removes the possibility of that interleaving entirely, without needing to establish or trust `StorageService`'s thread-safety under genuinely concurrent access, which nothing in this project currently does either way.

**The simultaneous-cross-dial race, addressed by explanation, not new code.** What happens if two peers with no existing session message each other at the same moment, each sending a PreKey-type message while also initiating their own outbound session? No custom handling exists here because none is needed: libsignal's own `SessionRecord` already carries a current chain plus prior ones specifically for this case, deterministically converging as messages decrypt against whichever state actually matches — `SessionManager`'s job is only to never bypass normal establish/encrypt/decrypt calls, which it doesn't. What *is* a real, explicit requirement from this: the `SignalProtocolStore` passed to `SessionManager`'s constructor must already be `SynchronizedSignalProtocolStore`-wrapped, since inbound processing and outbound sends can genuinely run concurrently against it.

**Verification, split honestly along the line this sandbox can actually test.** `handleDecryptedPlaintext` — the dispatch/dedup/persistence seam, needing neither jvm-libp2p nor libsignal-client — is real, executed, and passing: 4/4 checks against a real SQLite database (new message persisted and acknowledged; a duplicate not re-persisted but still acknowledged, since the sender's earlier ack may have been lost; a delivery receipt updating the original message's stored state; a read receipt marking this node's own messages read up to a timestamp), verified in part via direct SQL query since `StorageService` doesn't yet expose a reader for what it writes. The full network+crypto wiring (`start()`, `sendChatMessage()`) stub-compiles cleanly against hand-traced signatures already confirmed real by M6e-1's own build — same limitation as every jvm-libp2p+libsignal-touching piece of M6, now doubled up in one class for the first time. Two new demo Mains, `SessionManagerListenerMain`/`SessionManagerSenderMain`, prove the actual new capability this milestone claims — not a new demo mode, the same two roles ChatListenerMain/ChatSenderMain already had, except the listener now genuinely holds multiple concurrent, isolated sessions: run the listener once, run the sender against it as many times as you like, from as many different `-Pdatadir` values as you like, all at once.

**A concrete gap, found by the user's own multi-sender testing, not by review.** `SessionManagerListenerMain` publishes one pre-key bundle to a static file once, at startup, and never regenerates it. A later sender's handshake consumes the file's one-time prekey; the next sender reading that same now-stale file fails first contact — correctly, since the store doesn't care *why* the OPK is gone, only that it is. Not an adversarial-replay bug in `SessionManager` itself: a real, demo-only instance of the pre-key-lifecycle deferral already named above, and a useful early warning ahead of any further multi-sender testing hitting it again. Deliberately not patched in the demo — the actual fix is a daemon-level bundle-refresh policy, which needs discovery to exist as the real replacement for "hand someone a static bundle file" at all. Carried forward explicitly into M6f below, with one added condition: M6f's own design has to decide how a record's bundle gets *refreshed*, not only how it gets *signed*, or "M6f will handle it" quietly comes to mean only the signing half.

---

## M6f — Signed Discovery Record v2 ✅ (record shape, signing, and verification — the actual security-critical part of this milestone — fully confirmed by real, executed checks run directly against the real production source; the two new demo Mains that touch jvm-libp2p stub-compile against confirmed real signatures, same limitation as the rest of M6)

The gap M6e-2's own testing pointed at directly: discovery records can now carry a pre-key bundle, but only safely if a relay — malicious, compromised, or just careless — can't tamper with or substitute one in transit. An unsigned bundle-bearing record is a live MITM vector against PQXDH itself: whoever controls what a lookup returns controls which public keys the requester's session actually gets established against. New `core-discovery` module (deliberately not folded into `core-network` — see its own `build.gradle.kts` for the full dependency reasoning): `DiscoveryRecord` (addresses, optional pre-key bundle, optional relay preference, expiry) plus `SignedDiscoveryRecord`/`DiscoveryRecordCodec`, a hand-rolled length-prefixed binary codec matching every other wire codec in this project (`RelayFrameCodec`, `DiscoveryFrameCodec`, `PreKeyBundleCodec`) — not JSON — signed with the publisher's raw Ed25519 identity-key seed.

**The actual security check, and why it needed real research before any code got written.** Verifying a signature only proves "these bytes came from whoever holds this private key" — it says nothing about *whose* key that is, unless the verifier can independently tie the embedded public key to the peer ID being looked up. Since libp2p peer IDs for Ed25519 keys are, by construction, a hash of the key itself (the "identity" multihash of `protobuf(PublicKey{Type=Ed25519, Data=raw})`, from the libp2p peer-id spec), that binding is arithmetic, not a separate trust question — *if* the derivation is implemented correctly. `PeerId`'s own Javadoc had already flagged this exact tradeoff and explicitly deferred it, specifically because "reimplementing libp2p's exact multihash/protobuf encoding by hand" carries "a real risk of a subtle, hard-to-verify mismatch against what jvm-libp2p itself computes internally" without the ability to compile and run against the real library — and this sandbox still can't (no Maven Central access for jvm-libp2p's own dependency tree, same constraint every other M6 milestone has hit). What actually closed that gap this time: `Ed25519RecordKeys`' derivation was checked against the *official* libp2p peer-id spec's own published Ed25519 test vector (`github.com/libp2p/specs`, `peer-ids/peer-ids.md`) — protobuf encoding matched the spec's worked example byte-for-byte, and the derived peer ID matched a second, independently written cross-check (Python, a different base58 library, written before any Java code existed) exactly. On top of that, the whole pipeline — X.509 extraction, peer-ID derivation, sign, verify — was round-tripped against 5 real JDK-generated Ed25519 keypairs, confirming the fixed 12-byte X.509 prefix assumption two different ways rather than trusting it once. `PeerId`'s Javadoc now points here, rather than staying stale about a gap that's actually been addressed.

**The record format, and what deliberately isn't inside it.** No self-claimed peer-ID field: the embedded public key *is* the identity claim, checked against whatever peer ID the caller was actually looking up, so there's no second field that could disagree with it and create an authoritative-vs-claimed ambiguity bug. No separate version field either — the wire format's single leading marker byte (`0x02`) is the sole version/format discriminator, matching how `EncryptedFrame`/`RelayFrame`/`DiscoveryFrame` already do it elsewhere in this project rather than duplicating that concern. The marker sits outside the signed region on purpose: forging it just makes decoding fail cleanly (`MALFORMED`), since nothing downstream trusts it for anything beyond "which parser to use." Bounds-checked length-prefix reads throughout (max 16 addresses, 512 chars each, a 16 KB bundle ceiling) follow the same decoder-hardening discipline M5e's cleanup pass already applied project-wide.

**`DiscoveryRegistry`'s expiry peek is hygiene, not security — said explicitly, not left implicit.** The relay now best-effort withholds a record once its `expiresAt` has passed, using `decodeUnverified` — never `verifyAndDecode` — to read that one field. Deliberately not a signature check: the relay was never this system's trust boundary (a malicious relay operator could trivially skip this check and serve stale or tampered bytes anyway), so verifying signatures server-side would be security theater, not a real guarantee. The actual, load-bearing verification only ever happens client-side, in the peer doing the looking-up. This closes a real gap the `expiresAt` field would otherwise have had — a value nothing ever read is not a real expiry, just a decorative one — while staying fully backward-compatible: anything that doesn't decode as a well-formed V2 record (including the plain opaque bytes `PublishRecordMain` still publishes) is served exactly as before, unchanged.

**Verification, split honestly along the same line M6e-2 already established.** The security-critical half — the record codec, the Ed25519 key/peer-ID derivation, sign/verify, `DiscoveryRegistry`'s expiry logic — needs neither jvm-libp2p nor libsignal-client, and unlike most of M6's network-touching code, that meant it could actually be *run*, not just hand-traced or stub-compiled: 19/19 real, executed checks, against the real production source directly (this sandbox has no Maven Central access for JUnit/AssertJ either, so a standalone harness ran the actual classes; the equivalent `Ed25519RecordKeysTest`/`DiscoveryRecordCodecTest`/`DiscoveryRegistryTest` JUnit files are included for a real `./gradlew test` run on a machine that has that access). Covered: the spec-vector and independent-implementation cross-checks above; full record round-trip, both fully populated and minimal (no bundle, no relay preference); tampered-record rejection; cross-peer signature substitution rejected as `PEER_ID_MISMATCH` — the actual MITM scenario this milestone exists to close, exercised directly rather than only asserted possible; expired-record rejection; truncated, garbage, and implausible-address-count input all rejected as `MALFORMED` without over-allocating; `decodeUnverified`'s deliberate non-verification, confirmed rather than assumed; and `DiscoveryRegistry` correctly withholding an expired V2 record while still serving an unexpired one and any non-V2 payload untouched. The two new demo Mains, `PublishSignedRecordMain`/`LookupSignedRecordMain`, stub-compile against already-confirmed real signatures (`PeerNetworkService`, `DiscoveryController`, `PreKeyBundleFactory`) — same limitation as every jvm-libp2p-touching piece of M6 so far. One thing they add for free once run for real: printing both the libp2p peer ID the running host derives for itself and what `Ed25519RecordKeys` independently derives from the same public key, so the one live cross-check against the actual library that couldn't happen in this sandbox is one command away on a machine that can build jvm-libp2p.

**Explicitly out of scope, named rather than silently dropped: pre-key bundle refresh cadence.** This is the condition M6e-2's own finding attached to M6f in advance, and it's honored here rather than quietly narrowed: M6f does not decide *when* a discovery record's bundle should be regenerated and republished — that's a live-daemon-loop policy question (how often, triggered by what, how many one-time prekeys to keep in reserve), and no daemon loop exists yet to hang that policy off of; it's M6g/M6h's job once one does. What this milestone does establish, concretely, is that the record *format* was never what stood in the way: `DiscoveryController.publish()` is a plain overwrite (see `DiscoveryRegistry`'s own `overwriteExistingRecord` test), so republishing a fresh signed record, on whatever cadence eventually drives it, is just calling this same code again with a freshly generated bundle. Not claiming the deeper policy question is solved — it isn't — only that this milestone didn't let "M6f will handle it" quietly mean just the signing half.

---

## M6g-1 — `StorageService` read-side, plus an HLC clock-drift regression fix ✅ (confirmed by real, executed `./gradlew test` — 5/5 `SessionManagerReceivePipelineTest`, 14/14 `SqliteStorageServiceTest`, 39/39 full-repository `./gradlew test --rerun-tasks`)

**The regression fix, found during a general codebase review, not new M6g scope.** M5e's pre-M6 cleanup pass added `HybridLogicalClock.checkDrift(...)` — a real check, rejecting a message claiming an implausible future timestamp before it can corrupt this node's own clock — and wired it correctly into the demo Mains (`ChatListenerMain`/`ChatSenderMain`). `SessionManager` (M6e-2), which superseded those demos as the real daemon core, never picked the gate up: its `handleChatMessagePayload` called `clock.update(...)` inside a `catch` block for `RemoteTimestampRejectedException` — an exception only `checkDrift()` ever throws, never `update()` — so that catch was silently dead code, and every remote timestamp was accepted unconditionally in the actual production path. Fixed by calling `checkDrift()` first, before dedup and before persistence, matching `ChatListenerMain`'s own ordering exactly: a message that fails the check is now rejected outright — not persisted, not acknowledged, clock not advanced. `SessionManagerReceivePipelineTest` gained a new regression test proving the rejection, and confirmed the other four tests in that class (all using timestamps far within the drift bound) still pass unmodified.

**The read-side gap itself.** Every milestone through M6f only ever needed to *write* contacts and conversations, never list or look one up by id — so `StorageService` never had `listConversations`, `listContacts`, `getConversation`, or `getContact`, a gap this project's own `docs/M6g-gap-analysis-and-plan.md §1.1/§1.3` named directly. All four added to `StorageService`/`SqliteStorageService`: the two list methods return every row (contacts alphabetical, case-insensitive, `peer_id` as a deterministic tie-breaker; conversations most-recently-active first — by the most recent message's HLC physical-time component if any exist, `created_at` otherwise, computed via one aggregate query rather than one query per conversation), and the two lookups return `null` rather than throwing for an unknown id, matching this interface's existing convention.

**Verified for real, not hand-traced — `core-storage` needed no jvm-libp2p or libsignal-client to compile,** so a standalone harness compiled and ran the actual production classes directly against real `sqlite-jdbc` before any JUnit test existed for them, then real `SqliteStorageServiceTest` cases (case-insensitive/`null`-first contact ordering with a real name collision, and mixed message-bearing/message-less conversation ordering) confirmed the same logic through the project's actual test suite. The drift-guard fix inside `SessionManager` itself could not be compiled the same way — `SecureSessionService` needs `libsignal-client` on the classpath just to compile, same limitation as every jvm-libp2p/libsignal-touching piece of M6 — so that half was hand-traced against the file's own real method signatures and confirmed only once a real `./gradlew :node-daemon:test` run (on a machine with Maven Central access) came back green.

---

## M6g-2 — Peer routing table, invite codes, `ContactService` ✅ (sandbox-verified by 52 real, executed checks, then confirmed for real — `./gradlew :core-storage:test` 17/17, `./gradlew :node-daemon:test` `ContactServiceTest` 8/8 / `InviteCodeCodecTest` 7/7 / `PeerRoutingTableTest` 3/3, full-repository `./gradlew test --rerun-tasks` 39/39)

**What got built, and one thing that moved from the original plan.** `docs/M6g-gap-analysis-and-plan.md §2.3` sketched `PeerRoute` as a `node-daemon` class; it ended up in `core-storage.model` instead, alongside `Contact`/`Conversation` — it's a plain persisted row with the exact same status as those two, not itself an orchestration concern, and the plan's own §3 field list (dropping `hasSession` from §2.3's earlier draft) already pointed this way. `PeerRoutingTable` (`node-daemon`) still lives exactly where the plan put it, as a thin façade over three new `StorageService` methods (`upsertPeerRoute`, `getPeerRoute`, `listPeerRoutes`), backed by `V004__peer_routes.sql`. Two further deviations from the initial sketch, both explained where the code lives: no in-memory cache on top of `StorageService` (a second, potentially-stale copy of the same data, for a lookup that's fast enough against SQLite directly and isn't a hot loop — the same "don't add complexity ahead of a proven need" instinct `§2.5` already applied to the event-listener question), and no session-awareness baked into `PeerRoutingTable` at all (`SessionManager.hasSession(PeerId)` is already the source of truth for that; a future caller needing both joins them itself rather than this class carrying a `SignalProtocolStore` dependency it has no other reason to need).

**The merge-upsert, the actual non-trivial logic here.** A peer's route gets learned incrementally, from different places, at different times — a discovery lookup, `contacts.add`, and (once `SessionManager` is wired for it in M6g-3) an inbound message's `senderAddress`. `upsertPeerRoute` merges rather than overwrites: a `null` field on an incoming observation preserves whatever was already known for that field, while `last_seen` always advances to the new observation's time. Getting this wrong — a blind `REPLACE INTO` — would mean a partial observation (say, a relay registration that only knows a relay address) silently erasing a direct multiaddr some earlier observation had already established. Proven directly: three successive partial `upsertPeerRoute` calls (direct-only, then relay+name-only, then a fresh direct address + a pre-key bundle) confirmed each one preserves exactly what it should and overwrites exactly what it should, not by inspection but by 15 real assertions against a real SQLite database.

**Invite codes: `{"p": peerId, "d": discoveryAddress, "n": displayName}`, base64url JSON, no padding.** The one deliberate wire-format outlier in this project — every other codec here (`RelayFrameCodec`, `DiscoveryFrameCodec`, `ChatMessageCodec`, `PreKeyBundleCodec`) is hand-rolled length-prefixed binary, and this one genuinely isn't, on purpose: an invite code is base64-opaque either way, so the usual "binary is smaller, and the tooling already exists" case for the hand-rolled format doesn't actually apply here, and reusing M6c's already-built `JsonCodec` beats inventing an eighth format for a payload this small and this infrequent. No pre-key bundle inside the invite code itself — that would be a second, unsecured distribution channel for cryptographic material, defeating the entire point of M6f's signature verification; the bundle only ever arrives via a verified `DiscoveryRecord`.

**`ContactService.addContact`: decode → idempotency check → discovery lookup → verify → persist, and one thing it deliberately does *not* do.** §2.1 offered establishing a Signal session immediately as an optional last step; this implementation skips it. Session establishment spends one of the peer's one-time pre-keys — the exact finite, still-unmanaged resource M6e-2's own testing already found a real bug from, with refresh cadence still explicit, unsolved M6h scope. `SessionManager.sendChatMessage` already has the right mechanism for spending that resource exactly once, exactly when needed (its `bundleIfNoSessionYet` parameter, gated on `signalStore.containsSession(...)`, never speculative) — so `ContactService` stops at persisting the contact and the route, including the *raw, undecoded* pre-key bundle bytes on `PeerRoute` (matching `DiscoveryRecord.preKeyBundle()`'s own opaque-bytes convention), leaving the actual `PreKeyBundleCodec.decode` call to whatever composes `messages.send` later. A second, smaller correctness point: the `Contact` this creates is always `verified = false`, deliberately — a verified discovery-record signature proves the record wasn't forged in transit, a different guarantee from this project's own `Contact.verified()` (person-to-person, safety-number-style confirmation, still pre-release scope), and setting it `true` here would conflate the two. Idempotent by construction: `getContact` (M6g-1) is checked before anything else, so re-adding an already-known contact returns the existing record with no network round trip — `saveContact` is a plain `INSERT` against a `peer_id` primary key, and without this check a re-scanned QR code would hit a raw SQL constraint violation instead of a harmless no-op.

**Verified for real, including the case that actually matters.** `core-discovery` (M6f) needs neither jvm-libp2p nor libsignal-client to compile, and by keeping the pre-key bundle undecoded, `ContactService`, `PeerRoutingTable`, and `InviteCodeCodec` inherited that same property — every line of new production code in this milestone compiled and ran directly, no hand-tracing needed anywhere. 52 real, executed checks across three standalone harnesses run directly against the production classes: 15 covering the merge-upsert semantics above; 14 covering `InviteCodeCodec` round-trips (full and minimal invite codes, byte-for-byte re-encoding) and six distinct malformed-input rejections; and 23 end-to-end `ContactService` scenarios using genuinely Ed25519-signed discovery records (the same key-generation pattern `DiscoveryRecordCodecTest` established) — the happy path, idempotent re-add with no network call, peer-not-found, a configured-timeout lookup that never completes resolving cleanly rather than hanging, falling back to a daemon's default relay, a record with zero published addresses producing a route with a `null` direct multiaddr instead of a crash, and — the one that actually matters most — **a discovery record signed by the wrong Ed25519 key is rejected as `VERIFICATION_FAILED` and creates no contact**, the exact impersonation scenario M6f's signature check exists to close, exercised directly rather than only assumed to work because the codec has its own tests. The equivalent real JUnit files (`SqliteStorageServiceTest`'s new `peer_routes` cases, `PeerRoutingTableTest`, `InviteCodeCodecTest`, `ContactServiceTest`) are included for a real `./gradlew test` confirmation, not yet run at the time this was written.

---

## M6g-3 — `SessionManager` event emission, `DefaultFileTransferHandler`, `sendFile`/`acceptFileTransfer` ✅ (fully confirmed by real, executed `./gradlew test --rerun-tasks` — 9/9 `DefaultFileTransferHandlerTest`, 9/9 `SessionManagerReceivePipelineTest`, 18/18 `SqliteStorageServiceTest`, 4/4 `PeerRoutingTableTest`, 39/39 full repository tasks green; the live network+crypto wiring this consolidates — `DefaultFileTransferHandler`/`DaemonEventListener` plugged into a real `SessionManager` between two real processes over real jvm-libp2p — confirmed separately, on real hardware, via a dedicated checkpoint before M6g-4 started; see below)

**The chunk state machine is no longer a named gap.** `FileReceiverMain`/`FileSenderMain` (M4c/M4d) proved the wire mechanics work; `DefaultFileTransferHandler` consolidates that same proven logic into a real `FileTransferHandler` implementation `SessionManager` holds for its whole lifetime, serving any number of concurrent transfers with any number of peers rather than the demos' one-shot, single-transfer shape. This is a port, not a transcription — several deliberate behavioral differences from the demos' logic, each because the daemon context genuinely calls for it, are documented in full in `DefaultFileTransferHandler`'s own class Javadoc. The headline ones:

- **A real accept gate**, using `TransferState.OFFERED`/`ACCEPTED`/`IN_PROGRESS` distinctions the demo's logic declared but never actually used — `FileReceiverMain` requested chunks immediately on any offer; this project's own spec has a separate `files.accept` RPC method, so `onFileOffer` now stops at `OFFERED` and waits for `acceptFileTransfer` to actually be called before any chunk request goes out. Proven directly: an offer alone, with no accept, triggers zero chunk requests; accepting triggers exactly one.
- **Conversation IDs now match chat's** — the demo's ad-hoc `"direct-" + senderPeerId` placeholder predates `SessionManager.deriveDirectConversationId`'s canonical, sorted-pair scheme; a file transfer and a chat message between the same two peers now land in the same conversation, not two different ones.
- **Sends go through `OutboundMessageService`, not raw `sendEnvelope`** — the demos predate `ConnectionStrategy` (M6b) entirely, so a chunk reply now gets the same direct-then-relay fallback a chat message already does, via the same `FileTransferHandler.EncryptAndSend` seam `ContactService.DiscoveryLookup` (M6g-2) already established the pattern for.
- **No manual `CompletableFuture.runAsync` wrapping for the Netty-deadlock fix the demos needed** — verified, not assumed, by actually reading `OutboundMessageService.send`'s real implementation before relying on it: it already runs the blocking work on its own dedicated pool via `supplyAsync`, and this handler is only ever called from `SessionManager`'s `inboundExecutor`, never jvm-libp2p's own Netty event-loop thread the demos' fix was protecting against.
- **A hash mismatch is recorded, not just printed** — the demo's `completeTransfer` only ever printed "M4d FAILED" to the console; a real daemon transitions the transfer to `TransferState.FAILED`.
- **A re-offer for an already-accepted transfer doesn't un-accept it** — a genuine, narrow correctness gap a naive port would have reintroduced (a sender retrying its own offer send would otherwise discard the save path this daemon already chose for an in-progress transfer).

**A design decision reversed mid-implementation, kept visible rather than smoothed over.** The conversation-ID derivation above was first shared via a package-private `SessionManager.deriveDirectConversationId`, the same file/package-sharing call already made for two other trivial helpers. Attempting to actually verify `DefaultFileTransferHandler` in isolation surfaced a real cost that reasoning about it in the abstract hadn't: any reference to `SessionManager` forces the whole class to only ever compile alongside it — and `SessionManager.java` needs `libsignal-client`/`jvm-libp2p` just to compile, neither of which `DefaultFileTransferHandler` otherwise needs at all. Sharing four lines of pure string logic would have quietly cost this milestone real, executed verification of its single most substantial piece. Reverted to a small, explicitly-commented duplicate in `DefaultFileTransferHandler`, and `SessionManager`'s own visibility change reverted alongside it — found by actually attempting the standalone compile, not weighed correctly in advance.

**`DaemonEventListener` calls run on their own `eventExecutor`, never `SessionManager`'s `inboundExecutor`.** Flagged as an open design question back in the M6g-2 planning pass (that milestone's own gap-analysis review) and resolved here, at the same time the interface's shape was decided rather than discovered as a problem afterward: `inboundExecutor` being single-threaded exists for one narrow, load-bearing reason (serializing the dedup check-then-insert race), and a slow or stuck listener implementation — a real one will eventually do WebSocket I/O to a possibly-slow client — has nothing to do with what that thread protects. `SessionManager` gained a second, separate single-thread executor purely for dispatching listener calls, fire-and-forget.

**`sendFile`'s signature deliberately doesn't match the original plan's sketch.** `docs/M6g-gap-analysis-and-plan.md`'s M6g-3 section described `sendFile(PeerId, Path)` resolving addresses via `PeerRoutingTable` internally. Building that in would have made `sendFile` the only method on `SessionManager` that resolves its own addresses instead of receiving them — an inconsistency with `sendChatMessage`'s own already-proven signature that a caller can trivially avoid by resolving via `PeerRoutingTable` itself before calling either method, the same way it will already need to for chat. `sendFile` mirrors `sendChatMessage`'s shape exactly instead: explicit `directMultiaddr`/`relayMultiaddr` parameters, `SessionManager` still has no `PeerRoutingTable` dependency at all.

**`onNetworkStatusChanged()` fires with no payload, on purpose.** `SessionManager` alone cannot construct the full `network.status` shape §2.4 defines — that needs `PeerRoutingTable` data too, and `SessionManager` was deliberately kept unaware of `PeerRoutingTable` entirely back in M6g-2. Giving this callback a half-populated payload would be worse than giving it none. It fires as a bare "something worth re-checking just happened" signal at the two points `SessionManager` can actually detect a session transitioning from not-existing to existing — a transparent inbound PQXDH establishment (compared before/after a decrypt call, since libsignal handles that transparently and invisibly otherwise) and an explicit outbound `establishSession` call — leaving the real payload construction to whichever M6g-4 caller combines this signal with its own fresh `PeerRoutingTable.list()` and `SessionManager.hasSession(...)` queries.

**Subsequent audit hardening & lifecycle completeness:**
- **Lifecycle state differentiation in `onFileOffer`**:
  - `COMPLETED`: A re-offer for a transfer that already reached `COMPLETED` on disk is quietly ignored with zero listener notifications and zero redundant file-hashing I/O.
  - `IN_PROGRESS` / `ACCEPTED` (Crash Resumption): When a transfer was interrupted mid-way (e.g. 3 of 6 chunks written before daemon restart), a re-offer after restart allows `acceptFileTransfer` to check `missingChunks` and request **only** the genuinely missing chunks (`[3, 4, 5]`), assembling the whole file and verifying cleanly.
  - `FAILED` (Corruption Recovery): When a whole-file SHA-256 mismatch occurred, the transfer reached state `FAILED`. On re-offer, `storage.resetChunkState(transferId)` is executed inside an atomic transaction (`DELETE FROM file_chunk_state` + `UPDATE file_transfers SET state = 'OFFERED'`), allowing a clean retry where all chunks are requested fresh and re-verified.
- **`outgoingTransfers` TTL Eviction**: Sender-side transfers have no fourth wire ACK acknowledging the receiver is done. Tracked with `lastActivityEpochMs` (refreshed on every chunk served) and automatically evicted after a 24-hour TTL during `registerOutgoingTransfer`.
- **Relational Invariant Atomicity**: `StorageService.resetChunkState` is wrapped in `runInTransaction`, ensuring that intermediate illegal database states (`FAILED` state with cleared chunks) can never exist on disk, preserving relational consistency for upcoming M6g-4 query methods (`files.list`/`files.info`).
- **`PeerRoute` Restart Survival**: Added array-aware `equals`/`hashCode` for `preKeyBundle` and verified full route persistence across SQLite database reopenings.

**Verified for real — 39/39 tasks green across all modules.** Full repository test run (`./gradlew test --rerun-tasks`) confirmed: 9/9 `DefaultFileTransferHandlerTest` scenarios (single chunk, multi-chunk with short last chunk, accept gating, partial crash resumption requesting only missing chunks, whole-file hash mismatch detection, quiet ignore for completed offers, failed transfer retry, outgoing TTL eviction, and duplicate accept protection); 9/9 `SessionManagerReceivePipelineTest` cases (drift rejection, persistence, dedup, receipts, listener emission, pre-start checks, non-existent file rejection, and outbound delegation); and 18/18 `SqliteStorageServiceTest` cases.

**Real-hardware checkpoint (post-M6g-3, pre-M6g-4): the live wiring, confirmed for the first time.** Every claim above is real, but it's a claim about the testable seam — `DefaultFileTransferHandlerTest`/`SessionManagerReceivePipelineTest` both exercise this logic against fakes, not against a real `SessionManager` holding a real jvm-libp2p connection and a real libsignal-client session. Through the end of M6g-3, `SessionManagerListenerMain`/`SessionManagerSenderMain` (M6e-2's demo pair, still the only processes that run this code for real) still constructed a bare `new FileTransferHandler() {}` no-op and never passed a `DaemonEventListener` at all — so `DefaultFileTransferHandler` and every `DaemonEventListener` callback had never actually run between two real processes, only against fakes. Closed as a deliberate checkpoint before starting M6g-4, rather than letting the JSON-RPC layer get built on top of an unverified seam: both demo Mains now construct the real `DefaultFileTransferHandler` and a new, real (not test-fake) `PrintingDaemonEventListener` — prints every callback to the console, auto-accepts inbound file offers (there's no UI here to ask a person), and requires being handed the live `SessionManager` instance after construction, since `acceptFileTransfer` needs one to call back into (see that class's own Javadoc for why that ordering is a real constraint, not just tidiness).

Two real runs, on real hardware, both clean on the first attempt:
- **387-byte file, single chunk.** Chat message sent and `DELIVERED` receipt observed live for the first time (previously only inferable from SQLite after the process exited); file offer → auto-accept → one chunk request → one chunk served, decrypted, hash-verified, `COMPLETED`. Output byte-for-byte identical to the source file.
- **524,288-byte file, two chunks** (`FileChunker.DEFAULT_CHUNK_SIZE_BYTES` is 256 KiB, so this genuinely forced the multi-chunk path the first run couldn't reach). The receiver requested both chunks in one batch (`"2 chunk(s) requested"`, not one at a time), progress accumulated correctly across two separate `onFileChunk` calls (`1/2` → `2/2`), and the whole-file SHA-256 matched exactly on both sides.

**One real gap confirmed live, not newly discovered — this section already named it above** (`outgoingTransfers` TTL eviction: *"Sender-side transfers have no fourth wire ACK acknowledging the receiver is done"*). Both checkpoint runs' sender-side console output stops after `"[file] sending chunk N"` — no completion or hash-verification signal ever reaches the sender, because none exists: `eventListener.onFileTransferProgress(...)` is only ever called from the receiving side of `DefaultFileTransferHandler` (`onFileChunk`/`completeTransfer`), and there is no `FileTransferReceiptPayload`-style wire message anywhere in the codebase, unlike chat messages, which get both `DeliveryReceiptPayload` and `ReadReceiptPayload`. A real product would have no way to show "file delivered ✓" the way it can already show "message delivered ✓." Small, self-contained, and now empirically confirmed rather than just designed-around — tracked as its own line in [`docs/M6g-gap-analysis-and-plan.md`](docs/M6g-gap-analysis-and-plan.md)'s feature tracking matrix, targeted at M6h alongside this project's other already-deferred wire-protocol additions, rather than folded into M6g-4's own scope.

---

## M6g-4 — JSON-RPC router, method dispatch, push events, `DaemonErrorCode` ✅ (Verified — 29/29 unit tests in `JsonRpcRouterTest`, 20/20 `JsonRpcRequestTest`, 5/5 `JsonRpcResponseTest`, 4/4 `DaemonErrorCodeTest`, 9/9 `RpcJsonMapperTest` — 39/39 Gradle tasks green repository-wide)

Builds `JsonRpcRouter` — `docs/architecture-spec.md §7`'s JSON-RPC 2.0 surface, dispatching all 13 named methods against the real backends M6g-1 through M6g-3 built, plus forwarding every `DaemonEventListener` callback as an `event.*` push notification — against `docs/M6g-gap-analysis-and-plan.md §3`'s own implementation plan, researched against the real JSON-RPC 2.0 spec (batch requests, notification semantics, the `-32000`–`-32099` reserved server-error range) rather than approximated from memory.

**New:** `DaemonErrorCode` (the plan's exact ten values, mapped to real JSON-RPC codes), `JsonRpcRequest`/`JsonRpcResponse`/`JsonRpcError` (built on M6c's `JsonValue` tree, not a seventh hand-rolled format), `RpcJsonMapper` (domain record → wire JSON, kept separate from dispatch), and `JsonRpcRouter` itself.

**Two real gaps found and fixed in `SessionManager`, not just in the new code.** Building `messages.send`/`files.send` against their documented `{ messageId }`/`{ transferId }` response shapes found that `sendChatMessage`/`sendFile` generate those ids internally but never returned them — no way for any caller to learn which message or transfer their own request became, and no safe way to fake one (a router-generated id would silently mismatch the one already registered with `StorageService`/`FileTransferHandler`, corrupting later correlation). Fixed by changing both return types (`ConnectivityStatus` → `ChatSendResult`/`FileSendResult`, each pairing the real id with the existing status) — a small, targeted interface change, not a redesign; the one real call site of each (`SessionManagerSenderMain`, from the M6g-3 checkpoint) and `SessionManagerReceivePipelineTest`'s three `sendFile` assertions were updated to match.

**Two testability constraints found the same way — by actually trying to write the router's own tests, not anticipated in advance:**
- `DaemonWebSocketServer` (event push target) and `WebSocketSession` (per-connection handle passed into `onMessage`) are both `final`, with package-private constructors directly wrapping real Netty objects — neither can be constructed or subclassed in a unit test. Fixed with two small seams: `JsonRpcRouter.EventBroadcaster` (a one-method functional interface `DaemonWebSocketServer::broadcast` already satisfies, costing nothing in real wiring) in place of a direct `DaemonWebSocketServer` dependency, and a package-private `JsonRpcRouter.handle(String text)` — the actual request-handling logic, returning the response text (or `null`) rather than calling `session.send(...)` itself — factored out of `onMessage`, which now just calls `handle(...)` and forwards the result to the real session. Same pattern `ContactService.DiscoveryLookup` already established for the identical reason.

**`contentType` is currently rejected unless exactly `"text/plain"`.** `SessionManager.sendChatMessage` hardcodes `"text/plain"` for every outgoing `ChatMessagePayload` — confirmed by reading the real method body. Honoring a caller's stated `contentType` without it taking effect would silently misrepresent what was actually sent, so `messages.send` rejects anything else with a clear `Invalid params` error instead. Tracked as a real, named backend gap (`SessionManager`/`ChatMessagePayload` would need a real content-type field) — redesigning that wire payload is out of this router's own scope.

**Event forwarding covers all five `DaemonEventListener` callbacks, not just the three §7 happens to name as examples.** §7 predates M6g-3's finalized listener interface (this document's own header says it was written "between M6f (done) and M6g (next)"), so it could not have named `onDeliveryStateChanged`/`onFileOfferReceived` — dropping either would leave a real, already-built capability with no way to reach a client (no delivery ticks, no way to even learn a file offer arrived to decide whether to call `files.accept`). Forwarded as `event.message.deliveryStateChanged` and `event.file.offerReceived` alongside §7's own `event.message.received`, `event.transfer.progress`, `event.network.statusChanged`.

**`conversations.createGroup` and `files.cancel`** return `METHOD_NOT_FOUND` with a descriptive message, exactly per the plan — genuinely deferred (M8, M7 respectively), not stubbed silently.

---

## M6g-5 — Pre-M6h hardening pass 🧪 Tested (local run) (see [`docs/verification-vocabulary.md`](docs/verification-vocabulary.md)) — all 368 tests passing repository-wide across 49 Gradle tasks, with JaCoCo coverage reports generated

An experienced peer reviewer's audit (`docs/pre-m6h-hardening-plan.md`) found four real, independently-confirmed security gaps — confirmed by reading the actual source against the audit's claims line by line before acting on any of them, not taken on faith — plus process/hygiene issues. Addressed here as Phase 0 (hygiene) + Phase 1 (the four 🔴 findings) of that plan, ahead of `DaemonMain` per the audit's own reasoning: `C-1`'s relay-session work reshapes what a composition root would need to own, so it belongs before one exists, not after.

**D-1/D-2/D-3 (hygiene).** Added a verbatim `LICENSE` (AGPL-3.0 — diff-verified byte-identical against the FSF's own text, not paraphrased). CI now runs on every branch/PR instead of only `main`/`master`/`develop` (feature-branch work, including this pass, previously ran zero CI) and publishes JaCoCo coverage reports. Fixed a real contradiction between this README's M6g-4 section and `M6g-gap-analysis-and-plan.md §5` — resolved using the actual on-disk JUnit XML from that run, not just re-asserting one side's prose — and introduced the verification-vocabulary badges this section itself uses.

**C-2 — key/database files were world-readable by default.** `identity.key`, the Signal identity keypair, and `p2p-chat.sqlite` (holds Double Ratchet session state and plaintext message history) were all written with plain `Files.write`, inheriting the process umask. Fixed with owner-only (`rw-------`) permissions applied atomically at file-creation time in all three modules — not a `chmod` afterward, which leaves a real race window. Documented, not silently left: SQLite's WAL/journal sidecar files aren't covered by this (the driver creates them on demand, no per-file JDK hook exists) — real fix is `umask 077` in M6h's launch script, tracked there.

**C-4 — the filename sanitizer was a two-character denylist guarding a `resolve()` call.** Replaced with an allowlist (`[A-Za-z0-9._ ()-]`, leading dots stripped, Windows-reserved names rejected) plus a containment assertion on the resolved path as a whole — the actual load-bearing check, since it catches anything the allowlist misses. Found while fixing this, not named in the audit: `transferId` is exactly as peer-controlled as `fileName` and was being concatenated into the same path completely unsanitized; the containment assertion closes both at once.

**C-3 — `FileOfferPayload`'s `totalChunks`/`chunkSize`/`fileSize` were entirely wire-supplied and entirely trusted.** An offer claiming a multi-billion `totalChunks` would have built a multi-billion-entry `missingChunks` result before a single byte of the file existed. Now bounded (`chunkSize` 1 KiB–8 MiB, `totalChunks > 0`, `fileSize` under a new configurable cap) and checked for internal consistency (`totalChunks == ceil(fileSize/chunkSize)`) before any state is created; `onFileChunk` rejects an out-of-range `chunkIndex` before the `seek` and a decrypted plaintext longer than `chunkSize` (which would otherwise overwrite into the next chunk's region). Found while implementing this, not named in the audit: the mirror-image gap in `onFileChunkRequest` on the sending side, closed the same way.

**C-1 — the WebSocket server bound every interface, with no Origin check and no auth.** `bootstrap.bind(port)` binds `0.0.0.0`, not `127.0.0.1`, despite every doc comment already saying otherwise. Now binds loopback explicitly, requires an `Origin` allowlist match when an `Origin` header is present (empty by default until M7's real origin is known), and requires a fresh-per-run random token (written owner-only, same C-2 pattern) as a `?token=` query parameter. Verified against Netty's actual `4.2.10.Final` source (the version this project actually bundles, not assumed) before writing any of it: the naive version would have silently broken the WebSocket handshake entirely, since Netty's default path-matching is an exact string match that a `?token=...` query string fails against — `checkStartsWith` is what fixes that, and it isn't the default.

**Regression discipline.** C-3's new bounds broke 5 of `DefaultFileTransferHandlerTest`'s existing 10 tests, which used a `chunkSize=10` test fixture below the new 1 KiB minimum — found by tracing the new validation against every existing test by hand before considering this done, fixed by scaling the tests to realistic values while preserving exactly what each one tests, not by weakening the bound.

---

## Next milestone

M5a through M5e now cover the clock, the wire format, the live send/receive/persist loop, dedup/receipt state, and a pre-M6 hardening pass — all confirmed on real hardware, not just compiled. **M6 — the daemon + JSON-RPC/WebSocket API** is now underway, broken into sub-milestones following the same discipline M2–M5 used. M6a (shared dispatch), M6b (outbound send path), M6c (JSON value model), M6d (WebSocket transport), M6e-1 (persistent Signal session store — 39/39 tests), M6e-2 (`SessionManager`, the daemon core), and M6f (signed discovery records — 19/19 checks) are all done and verified.

**All four M6g sub-milestones are complete and verified, plus a hardening pass on top:**

- **M6g-1** ✅ — `StorageService` read-side expansion (`listConversations`, `listContacts`, `getConversation`, `getContact`), plus an HLC clock-drift regression fix found during review (see that section above).
- **M6g-2** ✅ — Peer routing table (`PeerRoutingTable` + `V004__peer_routes.sql`), invite code format and `InviteCodeCodec`, `ContactService` orchestrating the `contacts.add` flow via signed discovery lookups.
- **M6g-3** ✅ — `SessionManager` event emission (`DaemonEventListener`), `DefaultFileTransferHandler` consolidating M4a–M4d's proven chunk logic into a real, pluggable implementation, `sendFile()`/`acceptFileTransfer()` on `SessionManager`, full lifecycle state handling (`COMPLETED` deduplication, `FAILED` retry, partial crash resumption), and outgoing transfer TTL eviction. Its live wiring was separately confirmed on real hardware via a dedicated checkpoint (two clean two-process runs, single-chunk and multi-chunk).
- **M6g-4** ✅ — JSON-RPC router (`JsonRpcRouter`), method dispatch against all 13 of §7's named methods, push events extended to all five `DaemonEventListener` callbacks (not just §7's three named examples), and the `DaemonErrorCode` enum. Found and fixed two real gaps in `SessionManager` along the way (`sendChatMessage`/`sendFile` returning `ChatSendResult`/`FileSendResult`) and two testability constraints (`EventBroadcaster` and package-private `handle(text)` seam). Verified via full `./gradlew test` (39/39 tasks green repository-wide).
- **M6g-5** 🧪 — Pre-M6h hardening pass (see above). All four 🔴 findings from the audit fixed; verified via full `./gradlew test` (368/368 automated tests passing across 49 Gradle tasks repository-wide with JaCoCo coverage).

Then **M6h** (`DaemonMain`, the composition root + E2E test) picks up the remaining deferred items: relay-delivered inbound reception, pre-key bundle refresh cadence, the file-transfer completion receipt and locally-detected-send-failure push notification (both named above), and the live two-daemon-plus-JSON-RPC integration test. Real external/public address discovery also remains fully unbuilt and is M7's problem, not resolved by `DialableAddressResolver`'s LAN scope. Track B (protocol versioning) and Track A (relay as a real persistent transport, from the same hardening audit) remain, deliberately sequenced before M6h itself — see `docs/pre-m6h-hardening-plan.md`.
