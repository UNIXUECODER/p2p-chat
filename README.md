# p2p-chat

A decentralized, end-to-end encrypted peer-to-peer chat and file-sharing application. Built entirely in Java (JDK 21), with no central server controlling any user data. The architecture is designed so the UI layer (Electron desktop, Android) is a pure client of a local JSON-RPC 2.0 API — no frontend code ever touches cryptographic keys, libp2p internals, or the SQLite database directly.

For the full design rationale, module contracts, wire protocol, and storage schema, see [`docs/architecture-spec.md`](docs/architecture-spec.md).

---

## Project status

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
| M6 | `node-daemon` composition root + local JSON-RPC/WebSocket API (§7), 1:1 scope | 🔜 Planned |
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

Captured explicitly here, not resolved — a short agenda for M6's own first design pass, so none of it gets silently lost or rediscovered from scratch:

- **Canonical runtime peer identity.** M3d unified the *type* (one `PeerId` everywhere) but explicitly did not unify the *value* — `core-identity`'s hex ID and the libp2p-derived ID remain two different encodings of the same key, by deliberate decision (reimplementing libp2p's derivation by hand was judged too risky to do blind). A third value is already in play too: `ChatListenerMain`/`ChatSenderMain` currently use the app-identity hex ID as the `SignalProtocolAddress` name. M6 needs to decide which of these its API surface treats as canonical for routing/conversation IDs/UI-facing responses, and make sure Signal session addressing doesn't quietly stay a mismatched third if that's not decided explicitly.
- ~~**Shared decrypted-message dispatch.**~~ **Resolved in M6a** (see above) — `ApplicationMessageRouter` + `DispatchedMessage`, a thin marker-byte router in `node-daemon`, not a shared envelope or a merge of the two existing hierarchies.
- ~~**One outbound send path.**~~ **Resolved in M6b** (see above) — `OutboundMessageService`, wrapping the already-complete `ConnectionStrategy` for async execution off Netty callback threads plus an overall timeout, not new decision logic.
- **Discovery record contents.** Current records are opaque bytes carrying only addresses. M6 needs a concrete shape (addresses, current pre-key bundle?, relay preference, expiry, version) before discovery can replace manual address/bundle hand-carrying — and if pre-key bundles enter discovery, record signatures become security-critical; an unsigned bundle-bearing record is a MITM vector, not shipped as-is.
- **Pre-key bundle lifecycle.** Demo listeners publish a fresh bundle on every restart. **Partially addressed by M6e-1** — the *persistence mechanism* now exists (prekeys survive a restart, one-time prekeys are physically deleted on use, Kyber prekeys are marked-used rather than deleted) — but the *generation policy* (when bundles are regenerated, how many one-time prekeys to keep in reserve, whether/how signed prekeys rotate) is still undecided.
- **Storage transaction boundaries.** M5d's chat-receive path (save/upsert conversation, dedup check, save message, local read-state update) runs as separate, uncoordinated calls today — fine for a demo, not necessarily for a long-running daemon under concurrent load or a crash mid-sequence. `StorageService.runInTransaction` already exists; deciding what M6's receive path should wrap in it is real, bounded design work, not a general transactional overhaul.
- **Daemon-facing error vocabulary.** M6's API calls shouldn't expose raw exceptions as results — a small, deliberately small, set of cases (peer unreachable, relay unavailable, malformed record, crypto/session failure, duplicate message, storage failure, invalid request) is enough.
- **Principle, not a task:** treat every `node-daemon` demo `Main` as a proven mechanism, not final architecture. Their static-helper duplication, `System.out`-as-state-reporting, and one-shot-process assumptions were all correct choices for what they were proving — not patterns to carry into the real daemon.

---

## Next milestone

M5a through M5e now cover the clock, the wire format, the live send/receive/persist loop, dedup/receipt state, and a pre-M6 hardening pass — all confirmed on real hardware, not just compiled. **M6 — the daemon + JSON-RPC/WebSocket API** is next, and now underway, broken into sub-milestones (M6a–M6h) following the same discipline M2–M5 used rather than done as one pass. M6a (shared dispatch), M6e-1 (persistent Signal session store, deliberately built ahead of M6b–M6d since it's the highest-risk single piece — 39/39 tests confirmed on a real, live run against the real jar), and M6b (the outbound send path) are done. M6a and M6b compiled and ran directly against real production code in this sandbox — no jvm-libp2p stubs needed for either, since neither `core-messaging`/`core-filetransfer`/`core-model` nor `ConnectionStrategy`/`ConnectivityStatus`/`PeerNetworkService` have any jvm-libp2p or Netty dependency, confirmed by reading their imports rather than assumed. Remaining: M6c (a hand-rolled JSON value model), M6d (a hand-rolled WebSocket transport — RFC 6455, no new dependency), M6e-2 (wiring the M6e-1 store and M6b's send path into a live multi-session daemon core), M6f (signed discovery records — needed before pre-key bundles can safely enter discovery at all), M6g (the JSON-RPC method surface + push events, against §7 exactly as written rather than drifting from it), and M6h (`DaemonMain`, the actual composition root). Real external/public address discovery also remains fully unbuilt and is M6/M7's problem, not resolved by M5e's LAN-scoped `DialableAddressResolver`.
