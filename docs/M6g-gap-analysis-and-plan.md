# M6g Gap Analysis & Revised Implementation Plan

> This document was created between M6f (done) and M6g (next) after a systematic review of
> `architecture-spec.md §7`'s JSON-RPC API surface against the actual backend capabilities
> M6a–M6f delivered. The review revealed that M6g, as originally scoped ("implement
> `JsonRpcRouter` and standard methods/events"), implicitly assumed backend methods that don't
> exist yet. Rather than building those prerequisites inside M6g itself — turning one milestone
> into four — this document captures the gaps, resolves the design decisions they surface, and
> splits M6g into sub-milestones following the same M6e-1/M6e-2 pattern already established.

---

## 1. Gap Analysis: §7 Methods vs. Backend Capabilities

### 1.1 Method-by-method status

| §7 Method | Backend Exists? | Gap |
|:---|:---|:---|
| `identity.create` | `IdentityService.createIdentity()` ✅ | Must also create Signal identity (`SignalIdentityVault`) alongside. No combined path exists. |
| `identity.get` | `IdentityService.loadIdentity()` ✅ | Must return the **libp2p peer ID** (canonical runtime identity), not just the app-identity hex ID. |
| `contacts.add` | `StorageService.saveContact()` ✅ | **Major gap.** §7 says `{ inviteCode }`. No invite-code concept exists anywhere in the codebase. No mechanism to resolve a human-shareable token into peer ID + address + bundle. See §2.1 below. |
| `contacts.list` | ❌ **Does not exist** | `StorageService` has `saveContact()` but no `listContacts()`. |
| `conversations.list` | ❌ **Does not exist** | `StorageService` has `saveConversation()` but no `listConversations()`. |
| `conversations.createGroup` | ❌ **M8 scope** | Correctly deferred — CRDT membership + sender-key rotation. Still tracked in §7; annotated as M8. |
| `messages.send` | `SessionManager.sendChatMessage()` ✅ | Signature requires `directMultiaddr`, `relayMultiaddr`, `PreKeyBundle` — all things a JSON-RPC caller doesn't know. **Needs a peer-resolution layer** between the RPC surface and `SessionManager`. See §2.3 below. |
| `messages.history` | `StorageService.queryMessages()` ✅ | Works. `Pagination` model matches §7's `{ cursor, limit }`. |
| `files.send` | ❌ **No unified path** | `FileSenderMain` proves the mechanism, but `SessionManager` delegates to a no-op `FileTransferHandler`. No `sendFile()` on `SessionManager`. |
| `files.accept` | ❌ **Does not exist** | `FileReceiverMain` auto-accepts everything. No accept/reject flow. |
| `files.cancel` | ❌ **Does not exist** | No cancel mechanism. |
| `network.status` | Partial | `ConnectivityStatus` per-peer exists. No aggregated daemon status view. `relayConnected` will be `false` until M6h. Shape and semantics defined in §2.4 below. |
| `network.connectedPeers` | ❌ **Does not exist** | No peer roster. Defined as peers with an established Signal session (`hasSession == true` in `PeerRoutingTable`), not peers with a live TCP socket — libp2p connections are ephemeral. See §2.4. |

### 1.2 Push events vs. callback wiring

| §7 Push Event | Status |
|:---|:---|
| `event.message.received` | `SessionManager.handleChatMessagePayload()` persists + sends receipt — but has **no callback hook** to notify the WebSocket layer. |
| `event.transfer.progress` | `FileTransferHandler` is a no-op interface. Nothing emits progress. |
| `event.network.statusChanged` | **Does not exist.** No event source for connectivity changes. |

### 1.3 Structural gaps

1. **No peer-resolution layer.** `messages.send` in §7 takes a `conversationId`. `SessionManager.sendChatMessage()` needs a `directMultiaddr`, a `relayMultiaddr`, and optionally a `PreKeyBundle`. Who translates? Today: hand-carried CLI args. The daemon needs something that maps a peer ID to "how do I reach them and do I have a session with them."

2. **No event-emission pattern.** `SessionManager` persists and sends receipts, but has no way to notify the WebSocket layer "a new message arrived." M6g's JSON-RPC push events (`event.message.received`, etc.) need `SessionManager` to emit events, and the JSON-RPC layer to forward them. Neither has the hook.

3. **`StorageService` is write-heavy, read-light.** It can save contacts, conversations, messages. It can query messages by conversation. But it cannot list contacts, list conversations, get a conversation by ID, or get a contact by peer ID. Every `*.list` / `*.get` method §7 needs is missing.

4. **The `inviteCode` concept is undefined.** §7's `contacts.add({ inviteCode })` implies a human-readable exchange mechanism. Nothing anywhere in the codebase or spec defines what this is.

5. **File-transfer lifecycle is unintegrated.** `FileTransferHandler` (M6e-2) is a pluggable interface with default no-op methods. The actual chunk-looping, AES-GCM encryption/decryption, and resume logic proved in M4a–M4d (`FileSenderMain`/`FileReceiverMain`) need to be consolidated into a real implementation of this interface, callable via `SessionManager` rather than standalone Main classes.

---

## 2. Design Decisions Resolved

### 2.1 Invite code format

**Decision:** A base64url-encoded JSON payload containing:

```json
{ "p": "12D3KooW...", "d": "/ip4/.../tcp/9100/p2p/...", "n": "Alice" }
```

| Field | Required | Description |
|:---|:---|:---|
| `p` | Yes | Libp2p peer ID (canonical runtime identity) |
| `d` | No | Discovery/relay server multiaddr — defaults to this daemon's own configured relay if absent |
| `n` | No | Display name — cosmetic only, not trusted for identity |

**Rationale:**
- Contains exactly enough for first contact: the peer ID lets the recipient look them up via discovery (M6f); the discovery server address tells the recipient *where* to look; the display name gives the UI something to show before discovery completes.
- Does **not** contain a pre-key bundle — that's what signed discovery records (M6f) are for. Embedding a bundle in an invite code would create a second, unsecured distribution channel for cryptographic material, defeating the entire point of M6f's signature verification.
- Does **not** contain a direct multiaddr — peers behind NAT rarely have a stable one, and discovery + relay already solve reachability.
- Base64url (no `+`, `/`, or `=` padding) is safe for QR codes, URLs, and copy-paste.

**`contacts.add` flow:**
1. Decode the invite code → extract `p` (peer ID), `d` (discovery server), `n` (display name).
2. If `d` is present (or a default relay is configured), look up the peer via `DiscoveryController.lookup(peerId)`.
3. Verify the returned signed discovery record (M6f's `DiscoveryRecordCodec.verifyAndDecode`).
4. Extract addresses and pre-key bundle from the verified record.
5. Store contact via `StorageService.saveContact(...)`.
6. Store resolved addresses in the peer routing table (§2.3 below).
7. Optionally establish a Signal session immediately using the extracted bundle.

### 2.2 Canonical method namespace

**Decision:** Use §7's original names (`messages.send`, `messages.history`, `contacts.add`, etc.), not the M6-roadmap's alternatives (`chat.send`, `chat.listMessages`).

**Rationale:** §7 is the spec. The M6-roadmap was a working document. Where they disagree, the spec wins — it's what a future frontend team would build against.

### 2.3 Peer routing table

**Decision:** A new `PeerRoutingTable` class in `node-daemon`, not `core-network`.

Responsibilities:
- Maps `PeerId → PeerRoute { directMultiaddr, relayMultiaddr, hasSession, displayName, lastSeen }`.
- **Populated by:** discovery lookups (M6f), inbound `senderAddress` from chat/file-offer payloads, `contacts.add` flow, relay registrations.
- **Consulted by:** `messages.send` / `files.send` (so the RPC caller needs only a `conversationId`, not raw multiaddrs).
- **Persisted?** Yes, via a new `peer_routes` table (new migration `V004__peer_routes.sql`). A daemon restart shouldn't forget how to reach known peers.

**Why `node-daemon`, not `core-network`:**
- `core-network` is the libp2p abstraction layer. It doesn't know about contacts, conversations, or discovery records.
- The routing table is a daemon-level concern that crosses module boundaries (populated by `core-network` callbacks, `core-discovery` lookups, and `core-storage` data).

### 2.4 `network.status` and `network.connectedPeers` semantics

**The underlying question:** what does "connected" mean in a system where libp2p connections are ephemeral? The Envelope protocol opens a stream, sends bytes, and closes — there is no persistent connection pool. `SessionManager` doesn't track which peers are "online." The relay connection *is* long-lived, but `SessionManager.start()` doesn't connect to a relay yet (deferred to M6h).

**Decision — define "connected" as "has an established Signal session":** A peer you've exchanged keys with and can message at any time *is* connected in every way that matters to the UI. The frontend doesn't care whether there's a live TCP socket right now — it cares whether pressing "Send" will work. A Signal session means the answer is yes (assuming reachability, which is `ConnectionStrategy`'s job).

**`network.status` shape:**
```json
{
  "peerId": "12D3KooW...",
  "displayName": "Alice",
  "listenAddresses": ["/ip4/192.168.1.5/tcp/9000/p2p/12D3KooW..."],
  "relayConnected": false,
  "connectedPeerCount": 3
}
```

| Field | Source | Semantics |
|:---|:---|:---|
| `peerId` | `SessionManager.localPeerId()` | This daemon's canonical libp2p peer ID. Available after start. |
| `displayName` | `IdentityService.loadIdentity().displayName()` | Human-readable name set at identity creation. |
| `listenAddresses` | `PeerNetworkService.listenAddresses()` | Multiaddrs this daemon is listening on. |
| `relayConnected` | Relay connection state | **`false` until M6h** — `SessionManager` doesn't wire `RelayEventHandler` yet. Once M6h wires it, this reflects whether the daemon is currently registered with its configured relay server. |
| `connectedPeerCount` | `PeerRoutingTable` filtered by `hasSession == true` | Number of peers with an established Signal session — peers this daemon can encrypt messages to right now. |

**`network.connectedPeers` shape:**
```json
[
  {
    "peerId": "12D3KooW...",
    "displayName": "Bob",
    "lastSeen": 1724700000000,
    "hasSession": true
  }
]
```

Returns entries from `PeerRoutingTable` where `hasSession == true` (i.e., `signalStore.containsSession(...)` for that peer). `lastSeen` is the epoch-millis timestamp of the last message exchanged (sent or received). `displayName` comes from the contact record if one exists, otherwise from the most recent `ChatMessagePayload.senderAddress()` metadata.

**What this does NOT tell you:**
- Whether the peer is *currently online* — there's no heartbeat or presence protocol. A peer with `hasSession == true` who turned off their machine an hour ago still appears here. Real presence detection (ping probes, relay-mediated heartbeat) is a future concern, not M6g scope.
- Whether a direct connection will succeed vs. fall back to relay — that's `ConnectionStrategy`'s runtime decision, not something knowable in advance.

### 2.5 Event emission pattern

**Decision:** A simple listener interface, not a full event bus.

```java
public interface DaemonEventListener {
    void onMessageReceived(Message message);
    void onDeliveryStateChanged(String messageId, DeliveryState newState);
    void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize);
    void onFileTransferProgress(String transferId, int chunksReceived, int totalChunks, TransferState state);
    void onNetworkStatusChanged(/* shape from §2.4 */);
}
```

`SessionManager` calls these at the right points (after persistence, not before). The JSON-RPC router implements this interface to push `event.*` frames to all connected `WebSocketSession`s via `DaemonWebSocketServer.broadcast(...)`.

**Why not a generic event bus / pub-sub:** This project has exactly one event producer (`SessionManager`) and exactly one event consumer (the JSON-RPC layer). A generic bus would be abstraction without a second use case — the same reasoning that kept `FileTransferMessage` and `ChatWireMessage` as separate hierarchies until `ApplicationMessageRouter` gave them a real shared consumer.

### 2.6 File-transfer integration scope (M6 vs. deferred)

**Decision:** M6g includes a working `files.send`, `files.accept`, and basic `event.transfer.progress`. `files.cancel` is deferred to M7 (it needs UI-driven interruption semantics that don't exist without a frontend to drive them).

**Rationale:** M6 is described as "1:1 scope" and §7 lists all three `files.*` methods. The mechanism is fully proven (M4a–M4d). What's needed is a real `FileTransferHandler` implementation that consolidates `FileSenderMain`/`FileReceiverMain`'s proven logic, a `sendFile(peerId, filePath)` method on `SessionManager`, and an accept/reject flow. Non-trivial but well-understood, built from already-proven primitives.

---

## 3. Revised M6g Implementation Plan

The original M6g ("implement `JsonRpcRouter` and standard methods/events") is split into four sub-milestones, following the M6e-1/M6e-2 precedent:

```
M6f ✅ ──→ M6g-1 ──→ M6g-2 ──→ M6g-3 ──→ M6g-4 ──→ M6h
            │          │          │          │
            │          │          │          └─ JSON-RPC router,
            │          │          │             method dispatch,
            │          │          │             push events,
            │          │          │             error vocabulary
            │          │          │
            │          │          └─ SessionManager event emission
            │          │             + FileTransferHandler impl
            │          │
            │          └─ Peer routing table + invite code
            │             resolution + discovery integration
            │
            └─ StorageService read-side expansion
               (listConversations, listContacts, etc.)
```

### M6g-1 — StorageService read-side expansion

**Goal:** Fill the read-side gaps in `StorageService` that every `*.list` RPC method needs.

**New `StorageService` methods:**
- `List<Conversation> listConversations()` — all conversations, most-recently-active first (by most recent message's `hlc_timestamp`, or `created_at` if no messages yet).
- `List<Contact> listContacts()` — all contacts, alphabetical by display name.
- `Conversation getConversation(String conversationId)` — by ID, or `null` if not found.
- `Contact getContact(PeerId peerId)` — by peer ID, or `null` if not found.

**Scope note:** These are pure SQL reads against existing tables and the existing schema. No schema changes. No new dependencies. Same verification approach as every prior `StorageService` addition — unit tests executed against a real in-memory SQLite database.

**Verification:** Unit tests in `SqliteStorageServiceTest` verifying each query returns correct results, handles empty tables, respects ordering, and returns `null` / empty list on no-match.

---

### M6g-2 — Peer routing table + invite code resolution

**Goal:** Build the peer-resolution layer that `messages.send` and `contacts.add` need.

**New code:**
- **`PeerRoute`** record (`node-daemon`): `{ PeerId peerId, String directMultiaddr, String relayMultiaddr, String displayName, long lastSeen }`.
- **`PeerRoutingTable`** class (`node-daemon`): in-memory + persistent map of `PeerId → PeerRoute`. Backed by `V004__peer_routes.sql`.
- **`V004__peer_routes.sql`** migration (`core-storage`):
  ```sql
  CREATE TABLE peer_routes (
      peer_id           TEXT PRIMARY KEY,
      direct_multiaddr  TEXT,
      relay_multiaddr   TEXT,
      display_name      TEXT,
      last_seen         INTEGER NOT NULL
  );
  ```
- **`InviteCodeCodec`** (`node-daemon`): encode/decode the base64url JSON invite code defined in §2.1.
- **`ContactService`** (`node-daemon`): orchestrates the `contacts.add` flow (§2.1): decode invite code → discovery lookup → verify signed record → extract addresses + bundle → persist contact → populate routing table → optionally establish Signal session.

**Scope note:** `ContactService` calls `DiscoveryController.lookup(...)`, which requires a live connection to a discovery server. For testability, the discovery lookup is injected as a functional interface (same pattern `SessionManager` uses with `FileTransferHandler`), not hardcoded.

**Verification:** Unit tests for `InviteCodeCodec` (round-trip, missing fields, malformed input). Unit tests for `PeerRoutingTable` (CRUD, persistence across simulated restart). Integration-style test for `ContactService` with a fake discovery lookup returning a pre-built signed record.

---

### M6g-3 — SessionManager event emission + FileTransferHandler implementation

**Goal:** Give `SessionManager` a way to notify the WebSocket/JSON-RPC layer when things happen, and wire in the file-transfer lifecycle.

**Changes to `SessionManager`:**
- New `DaemonEventListener` interface (§2.5).
- `SessionManager` takes an optional `DaemonEventListener` (constructor parameter or setter).
- New call sites:
  - After `handleChatMessagePayload` persistence: `listener.onMessageReceived(message)`.
  - After receipt-driven state update: `listener.onDeliveryStateChanged(messageId, newState)`.
  - After file-transfer handler events: `listener.onFileOfferReceived(...)`, `listener.onFileTransferProgress(...)`.
- New `sendFile(PeerId, Path)` method — initiates a file offer using `PeerRoutingTable` for address resolution.
- New `acceptFileTransfer(String transferId, Path savePath)` method — accepts a pending file offer and starts chunk retrieval.

**`DefaultFileTransferHandler` implementation:**
- Consolidates `FileSenderMain`/`FileReceiverMain`'s proven logic into a class that plugs into `SessionManager`.
- Handles the chunk request/response loop, AES-GCM encryption/decryption, and resume via `StorageService.markChunkReceived()` / `missingChunks()`.
- Calls `DaemonEventListener` at the right points for progress and completion.

**Scope note:** This is the largest sub-milestone. The file-transfer handler is real work — but it's porting already-proven logic from demo Mains, not inventing new mechanics.

**Verification:** Unit tests for `DaemonEventListener` emission (mock listener, verify callbacks). Unit tests for `DefaultFileTransferHandler` chunk logic against real SQLite + real `FileChunker`/`ChunkCipher`. Deferred to M6h: live two-daemon file transfer over real libp2p.

---

### M6g-4 — JSON-RPC router, method dispatch, push events, error vocabulary

**Goal:** The original M6g scope — now actually buildable, because M6g-1 through M6g-3 provided everything it needs to call.

**New code:**
- **`JsonRpcRequest`** / **`JsonRpcResponse`** / **`JsonRpcError`** records (`node-daemon`): built on top of M6c's `JsonValue`/`JsonCodec`. Envelope parsing/serialization for JSON-RPC 2.0.
- **`DaemonErrorCode`** enum: `PEER_UNREACHABLE`, `RELAY_UNAVAILABLE`, `MALFORMED_RECORD`, `CRYPTO_FAILURE`, `DUPLICATE_MESSAGE`, `STORAGE_FAILURE`, `INVALID_REQUEST`, `METHOD_NOT_FOUND`, `UNKNOWN_CONVERSATION`, `UNKNOWN_CONTACT`. Mapped to JSON-RPC 2.0 error codes (`-32600` range for standard, `-32000` range for application-specific).
- **`JsonRpcRouter`** implements `WebSocketTextHandler`: parses incoming JSON-RPC requests via `JsonCodec`, dispatches to the right backend call, serializes results via `JsonCodec`, and sends JSON-RPC responses back through `WebSocketSession.send(...)`.
- **`JsonRpcRouter`** also implements `DaemonEventListener`: translates backend events into `event.*` push notifications, broadcasting them to all connected WebSocket sessions via `DaemonWebSocketServer.broadcast(...)`.

**Method mapping (complete §7 surface):**

| §7 Method | Backend Call | Notes |
|:---|:---|:---|
| `identity.create` | `IdentityService.createIdentity()` + `SignalIdentityVault.loadOrCreate()` | Combined path — both identities created together |
| `identity.get` | `IdentityService.loadIdentity()` + `SessionManager.localPeerId()` | Returns both app-identity and canonical libp2p peer ID |
| `contacts.add` | `ContactService.addContact(inviteCode)` | Full flow from §2.1 |
| `contacts.list` | `StorageService.listContacts()` | From M6g-1 |
| `conversations.list` | `StorageService.listConversations()` | From M6g-1 |
| `conversations.createGroup` | Returns `DaemonErrorCode.METHOD_NOT_FOUND` | Explicitly deferred to M8; error message says "available in a future version" |
| `messages.send` | `SessionManager.sendChatMessage(...)` via `PeerRoutingTable` | PeerRoutingTable resolves conversationId → peer addresses |
| `messages.history` | `StorageService.queryMessages(...)` | Already works as-is |
| `files.send` | `SessionManager.sendFile(...)` via `PeerRoutingTable` | From M6g-3 |
| `files.accept` | `SessionManager.acceptFileTransfer(...)` | From M6g-3 |
| `files.cancel` | Returns `DaemonErrorCode.METHOD_NOT_FOUND` | Deferred to M7; needs UI-driven interruption |
| `network.status` | Aggregated from `SessionManager.localPeerId()` + `PeerNetworkService.listenAddresses()` + `PeerRoutingTable` | `relayConnected` = `false` until M6h; `connectedPeerCount` = peers with `hasSession == true` — see §2.4 |
| `network.connectedPeers` | `PeerRoutingTable` entries filtered by `hasSession == true` | Returns peer ID, display name, last seen timestamp — "connected" = established Signal session, not live TCP socket (§2.4) |

**Verification:** Unit tests for JSON-RPC envelope parsing (valid requests, batch requests, malformed input). Unit tests for each method handler (mock backend, verify JSON response shape). Unit tests for error response formatting (every `DaemonErrorCode` mapped correctly). Unit tests for push event emission (mock WebSocket session, verify `event.*` frame content). Deferred to M6h: live end-to-end test with a real WebSocket client.

---

## 4. Feature Tracking Matrix

Every planned feature, where it's tracked, and what milestone it belongs to. **Nothing is dropped; deferred items are explicitly named.**

| Feature | Current Status | Target Milestone | Notes |
|:---|:---|:---|:---|
| 1:1 chat send/receive | ✅ Proven (M5c/M5d) | M6g-4 (API exposure) | Core mechanism done; needs RPC surface |
| Message dedup | ✅ Built (M5d/M6e-2) | — | Complete in `SessionManager` |
| Delivery receipts | ✅ Built (M5d/M6e-2) | — | Complete, auto-sent by `SessionManager` |
| Read receipts | ✅ Built (M5d/M6e-2) | M6g-4 (API exposure) | Mechanism complete; needs RPC trigger |
| HLC ordering | ✅ Built (M5a) | — | Complete |
| File transfer (single-peer, chunked, encrypted) | ✅ Proven (M4a–M4d) | M6g-3 (handler integration) | Mechanism proven; needs `SessionManager` integration |
| File transfer resume | ✅ Proven (M4d) | M6g-3 | |
| File accept/reject flow | ❌ Not started | M6g-3 | Currently auto-accepts in demo Mains |
| File cancel | ❌ Not started | **M7** | Needs UI-driven interruption semantics |
| Signed discovery records | ✅ Built (M6f) | — | Complete |
| Pre-key bundle via discovery | ✅ Record format supports it (M6f) | M6g-2 (wired into `contacts.add`) | |
| Pre-key bundle refresh cadence | ❌ Not started | **M6h** | Needs daemon loop to schedule; see README M6e-2 section |
| Persistent Signal sessions | ✅ Built (M6e-1) | — | Complete (39/39 tests) |
| Multi-peer SessionManager | ✅ Built (M6e-2) | — | Complete |
| Relay-delivered inbound reception | ❌ Not wired | **M6h** | `SessionManager` registers `OnEnvelopeMessage` only, not `RelayEventHandler` |
| JSON-RPC 2.0 over WebSocket | Transport ✅ (M6d), JSON ✅ (M6c) | M6g-4 (RPC layer) | |
| Invite code mechanism | ❌ Designed here (§2.1) | M6g-2 | |
| Peer routing / address book | ❌ Designed here (§2.3) | M6g-2 | |
| StorageService read-side queries | ❌ Not started | M6g-1 | |
| Event emission to frontend | ❌ Designed here (§2.5) | M6g-3 | |
| `conversations.createGroup` | ❌ M8 scope | **M8** | CRDT membership + sender-key rotation |
| Group chat CRDT membership | ❌ Not started | **M8** | OR-Set, `crdt_ops_log` table ready |
| Sender-key group encryption | ❌ Not started | **M8** | §8's sender-key scheme |
| Electron frontend | ❌ Not started | **M7** | |
| OS keychain integration | ❌ Not started | **Pre-release** | Keys currently plaintext on disk (§8 known gap) |
| Safety number verification | ❌ Not started | **Pre-release** | §8's out-of-band identity verification |
| Message retry / reliability | ❌ Not started | **M7 or later** | No retry policy exists; `OutboundMessageService` is fire-once |
| Offline message queuing | ❌ Not started | **Post-M6** | Relay is in-memory only; no persistence |
| STUN / external address discovery | ❌ Not started | **M7 or later** | `DialableAddressResolver` is LAN-scoped only |
| mDNS LAN discovery | ❌ Not started | **M7 or later** | Available in jvm-libp2p (beta) |
| Android port | ❌ Not started | **M9** | |
| Relay offline message persistence | ❌ Not started | **Post-M6** | Relay currently in-memory only |

---

## 5. Relationship to M6h

M6h (`DaemonMain` composition root) is **unchanged in purpose** — it remains the final assembly step where every daemon component is wired together in one `main()`, with a Gradle task `:node-daemon:runDaemon`. What changes: it now has more pieces to wire (M6g-1 through M6g-3 add `PeerRoutingTable`, `ContactService`, `DaemonEventListener`, and `DefaultFileTransferHandler`), and it picks up the explicitly-deferred items from M6e-2 that require a live daemon loop:

- **Relay-delivered inbound reception**: wire `SessionManager` as a `RelayEventHandler`, not just `OnEnvelopeMessage`.
- **Pre-key bundle refresh cadence**: schedule periodic republication of signed discovery records with fresh bundles.
- **Automated E2E test**: two daemon processes exchange chat messages and file transfers via JSON-RPC, verifying the full stack end-to-end.
