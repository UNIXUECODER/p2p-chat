# Decentralized P2P Chat & File-Sharing App — Architecture & API Specification (v0.2)

## 1. Design principles

1. **Core is UI-agnostic.** All P2P logic, crypto, storage, and networking live in Java modules that expose *only* a stable, versioned local API. The Electron/JS frontend is a client of that API — nothing else. This is what protects the project from drastic rewrites later: swap the UI framework, add an Android client, add a CLI — none of it touches the core.
2. **Security is not optional or bolted on.** Every message and file is end-to-end encrypted before it touches the network, from the very first commit. There is no "add encryption later" milestone.
3. **Version everything from day one.** Every wire message carries a `protocol_version`. Every local API call is namespaced (`v1.*`). Every peer-to-peer handshake exchanges a capability list. This means adding new message types or fields later is additive, not breaking — old and new nodes can coexist.
4. **Reserve extension points even for features you're deferring.** Example: v1 supports one device per identity, but every message already carries a `device_id` field (always `"0"` for now). When multi-device lands, the wire format doesn't change — only new logic reading a field that already existed.
5. **Local-first.** All data lives on the user's device (SQLite). Nothing is synced to any cloud the project controls, because no such cloud exists.
6. **Direct P2P is preferred, relay is fallback, never a third state.** Every connection is either DIRECT or RELAYED — the app always knows which, and surfaces it (this matters for UX and for debugging later).

### Non-goals for v1 (explicitly deferred, not forgotten)
- Multi-device sync (single device per identity for now — see §13 for the hook)
- Voice/video calls (same peer-connection layer will support it later via WebRTC data/media channels)
- Spam/abuse moderation tooling (local block/report only — no central authority to appeal to)
- Interop with other chat protocols (Matrix, XMPP, etc.)

Being explicit about what is *out* of scope now is itself a "no drastic changes later" decision — it stops the v1 design from silently assuming things it has not actually built.

---

## 2. System overview

```
 JS / Electron UI  <── local WebSocket (JSON-RPC) ──>  Java node-daemon
                                                              │
                                              ┌───────────────┼───────────────┐
                                        core-identity   core-network    core-storage
                                        core-crypto      core-discovery  core-groups
                                                          core-messaging core-filetransfer
                                                              │
                                                    libp2p transport (TCP)
                                                              │
                                        ┌─────────────────────┼─────────────────────┐
                                  direct peer            relay-server          bootstrap/discovery node
```

The Java process (`node-daemon`) is a long-running local process. The Electron app starts it (or connects to an already-running instance) and talks to it purely through the local API defined in §7. Nothing in the UI ever touches libp2p, crypto keys, or SQLite directly.

The `relay-server` is a separately deployable process — anyone can run one. It serves two co-located roles: relaying application traffic between peers that cannot reach each other directly (M3a), and acting as the peer discovery server (M3c). Both roles use the same stable libp2p identity and the same TCP port.

---

## 3. Repository / module structure

```
p2p-chat/
├── settings.gradle.kts
├── build.gradle.kts                  # root, multi-module
├── core-identity/                    # keypair generation, identity storage, key vault
├── core-model/                       # M3d: shared value types (PeerId, DeviceId) — not in this list originally;
│                                      # added when core-network's public API needed to stop leaking
│                                      # io.libp2p.core.PeerId and core-storage needed a real peer_id type
├── core-crypto/                      # PQXDH session establishment, Double Ratchet, EncryptedFrame wire format
├── core-network/                     # jvm-libp2p integration: transport, Envelope/Relay/Discovery protocols
├── core-storage/                     # M3d: SQLite persistence layer, migrations (schema in §9)
├── core-filetransfer/                # M4a/M4b: chunking, per-chunk AES-256-GCM encryption, wire payload codecs (swarm transfer deferred — see §12, M8)
├── core-messaging/                   # M5a: HybridLogicalClock. M5b: chat wire payloads + codec. M5c: ChatListenerMain/ChatSenderMain in node-daemon, real bidirectional 1:1 chat. M5d: dedup + receipt state transitions. M5e: HLC remote-drift guard
├── node-daemon/                      # composition root — wires core-* modules, milestone demo entry points
├── relay-server/                     # standalone deployable relay + discovery node (headless, anyone can run one)
└── docs/
    └── architecture-spec.md          # this document

# Modules from the architecture spec not yet scaffolded — added as each milestone is reached:
# core-discovery/                     # DiscoveryService interface and client-side implementation
# core-groups/                        # CRDT group membership/state, sender-key group encryption
# api-contract/                       # shared .proto + JSON-RPC schema, generated code
# client-desktop/                     # Electron + React/TypeScript UI
# client-android/                     # Kotlin UI — reuses core-* modules directly (same JVM)
```

Each `core-*` module is a separate Gradle subproject. `node-daemon` depends on interfaces, not concrete classes, and any module can be swapped or mocked in tests. Dependencies whose types appear in a module's public API are declared as `api(...)` (with `java-library` applied); pure implementation dependencies are `implementation(...)`.

---

## 4. Domain model (core entities)

**`PeerId` and `DeviceId` are implemented as of M3d**, in `core-model` — see that module's Javadoc for one caveat this sketch doesn't capture: unifying the *type* used everywhere did not also unify the *value* — `core-identity`'s own identity string and the libp2p-derived peer ID used for routing are still two different encodings of the same underlying key (documented in the M1.5 README section). Below is the original design sketch, kept for the rest of the domain model, which is not yet implemented beyond `Identity`/`Contact`/`Message`/`FileTransfer`'s use in `core-storage`.

**`Conversation` gained a real, but narrower, implementation as of M4e** — `core-storage.model.Conversation` matches this sketch's `conversationId`/`type`/`name`/`createdAt` fields but deliberately omits `members`. It exists to close the `messages.conversation_id REFERENCES conversations` foreign-key gap flagged at the end of M4d (see the M4e section of README.md for the full story), not to be the real conversation/membership API — that's `conversation_members`' own read/write access pattern, which is M5 (for a DIRECT conversation's two participants) and M8 (for a GROUP's roster) design work, not something to guess at here.

```java
// A peer's identity is derived from their public key — no central registry assigns it.
public record PeerId(String value) {}          // base58-encoded multihash of the identity public key
public record DeviceId(String value) {}        // "0" for v1; reserved for multi-device

public record Identity(
    PeerId peerId,
    String displayName,
    byte[] identityPublicKey,
    long createdAt
) {}

public record Contact(
    PeerId peerId,
    String displayName,
    boolean verified,        // true once safety-number/QR verification has happened
    long addedAt
) {}

public enum ConversationType { DIRECT, GROUP }

public record Conversation(
    String conversationId,
    ConversationType type,
    String name,             // null for DIRECT (derived from contact name)
    Set<PeerId> members,
    long createdAt
) {}

public enum DeliveryState { SENDING, SENT, DELIVERED, READ, FAILED }

public record Message(
    String messageId,
    String conversationId,
    PeerId senderPeerId,
    DeviceId senderDeviceId,
    String hlcTimestamp,     // hybrid logical clock — see §11 for why not wall-clock
    String contentType,      // "text/plain" | "text/markdown" | "file-ref" | "system"
    byte[] plaintext,        // decrypted, in-memory / local-storage only — never serialized to wire
    DeliveryState state
) {}

public enum TransferState { OFFERED, ACCEPTED, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED }

public record FileTransfer(
    String transferId,
    String conversationId,
    String fileName,
    long fileSize,
    String fileHash,         // SHA-256 of full plaintext — also the content-addressed identifier
    int chunkSize,
    int totalChunks,
    TransferState state
) {}
```

---

## 5. Core Java service interfaces

These are the contracts `node-daemon` composes. Each is implemented by exactly one `core-*` module and consumed via its interface elsewhere — no module reaches into another's internals.

```java
// core-identity
public interface IdentityService {
    Identity createIdentity(String displayName);
    Identity loadIdentity() throws IdentityNotFoundException;
    boolean hasIdentity();
    /**
     * The raw 32-byte Ed25519 private key seed (RFC 8032 format) backing this identity.
     * core-network uses this to derive a stable libp2p peer identity from the SAME keypair,
     * instead of generating a random one per process. Returns raw bytes rather than a
     * libp2p-specific type, so core-identity has no dependency on the networking library.
     */
    byte[] rawPrivateKeySeed();
}

// core-network
public interface PeerNetworkService {
    // 3-arg: M1/M1.5/M2b/M2c — Envelope only
    void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage);
    // 4-arg: M3a — Envelope + Relay
    void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
               RelayEventHandler relayEventHandler);
    // 5-arg: M3c — Envelope + Relay + Discovery (relay-server only)
    void start(int listenPort, byte[] identityKeySeed, OnEnvelopeMessage onEnvelopeMessage,
               RelayEventHandler relayEventHandler, DiscoveryRequestHandler discoveryRequestHandler);

    void stop() throws Exception;
    String[] listenAddresses();
    long pingPeer(String multiaddr) throws Exception;
    void sendEnvelope(String multiaddr, byte[] data) throws Exception;
    void sendEnvelope(String multiaddr, byte[] data, long timeoutMillis) throws Exception; // M3b
    RelayController connectToRelay(String relayMultiaddr, RelayEventHandler onEvent) throws Exception;
    DiscoveryController connectToDiscovery(String discoveryMultiaddr) throws Exception; // M3c
}

// core-crypto
public interface SecureSessionService {
    void establishSession(SignalProtocolAddress remote, PreKeyBundle remoteBundle) throws Exception;
    EncryptedFrame encrypt(SignalProtocolAddress remote, byte[] plaintext) throws Exception;
    byte[] decrypt(SignalProtocolAddress remote, EncryptedFrame frame) throws Exception;
}

// core-discovery (not yet scaffolded — interface planned)
public interface DiscoveryService {
    CompletableFuture<List<PeerAddrInfo>> findPeer(PeerId peerId);
    void announce(PeerId self, List<Multiaddr> addrs);
    void addBootstrapNode(Multiaddr addr);
}

// core-messaging (module scaffolded in M5a, but only HybridLogicalClock so far — this
// MessagingService interface itself is still just the original sketch, not yet implemented;
// see the M5a section of README.md for what's actually there today)
public interface MessagingService {
    String sendMessage(String conversationId, MessageContent content);
    void onMessageReceived(Consumer<Message> handler);
    void markDelivered(String messageId);
    void markRead(String messageId);
    List<Message> getHistory(String conversationId, Pagination page);
}

// core-groups (not yet scaffolded)
public interface GroupService {
    String createGroup(String name, Set<PeerId> members);
    void addMember(String conversationId, PeerId peer);
    void removeMember(String conversationId, PeerId peer);
    GroupState currentState(String conversationId);   // CRDT-merged, always convergent
    void applyRemoteOp(String conversationId, GroupOp op);
}

// core-filetransfer — module scaffolded across M4a-d (chunking/encryption primitives, wire
// payload codecs, real networked transfer, storage-backed resume — see the README). This
// orchestration-level interface itself — offering a transfer, tracking progress, pause/resume
// — was never implemented; M4's demo Mains (FileSenderMain/FileReceiverMain) proved the
// underlying mechanics work without needing this formal API shape. Revisit if/when a client
// (M6) needs a real callable interface rather than console output.
public interface FileTransferService {
    String offerFile(String conversationId, Path localFile);
    void acceptTransfer(String transferId, Path saveLocation);
    void onTransferProgress(Consumer<TransferProgress> listener);
    void pauseTransfer(String transferId);
    void resumeTransfer(String transferId);
    void cancelTransfer(String transferId);
}

// core-storage — scaffolded in M3d, matching this sketch exactly at first (see core-storage's
// StorageService.java for the real interface and its scope note on what's deliberately not
// here yet — full conversation/group/chunk-level methods, left for M4/M5/M8). markChunkReceived
// / missingChunks were added in M4d once file transfer needed them; saveConversation was added
// in M4e to close a foreign-key gap the original five-method sketch didn't anticipate — see
// that method's Javadoc for why it's narrower than full conversation management.
public interface StorageService {
    void saveMessage(Message message);
    List<Message> queryMessages(String conversationId, Pagination page);
    void saveContact(Contact contact);
    void saveFileMetadata(FileTransfer transfer);
    void saveConversation(Conversation conversation);          // M4e
    void markChunkReceived(String transferId, int chunkIndex); // M4d
    List<Integer> missingChunks(String transferId, int totalChunks); // M4d
    <T> T runInTransaction(Supplier<T> work);
}
```

---

## 6. Wire protocol (peer-to-peer, protobuf)

Every byte that crosses the network — direct or relayed — is one of these. `Envelope` is the outer shell; everything inside `encrypted_payload` is ciphertext until decrypted by `SecureSessionService`.

```protobuf
syntax = "proto3";
package p2pchat.v1;

message Envelope {
  string protocol_version   = 1;   // "1.0" — bump on breaking change, never reuse a version string
  string sender_peer_id     = 2;
  string sender_device_id   = 3;   // "0" in v1; reserved for multi-device
  string message_id         = 4;   // UUID — used for de-duplication on receipt
  string hlc_timestamp      = 5;   // hybrid logical clock string, see §11
  EnvelopeType type         = 6;
  bytes  encrypted_payload  = 7;   // Double-Ratchet ciphertext, decrypts to one message below
}

enum EnvelopeType {
  HANDSHAKE_INIT      = 0;
  HANDSHAKE_RESPONSE  = 1;
  CHAT_MESSAGE        = 2;
  DELIVERY_RECEIPT    = 3;
  READ_RECEIPT        = 4;
  GROUP_OP            = 5;
  FILE_OFFER          = 6;
  FILE_CHUNK_REQUEST  = 7;
  FILE_CHUNK          = 8;
  PRESENCE_PING       = 9;
}

message HandshakeInitPayload {       // PQXDH
  bytes identity_key      = 1;
  bytes ephemeral_key     = 2;
  bytes signed_prekey     = 3;
  bytes prekey_signature  = 4;
  bytes one_time_prekey   = 5;       // optional
  bytes kyber_prekey      = 6;       // Kyber-1024 post-quantum prekey
  bytes kyber_signature   = 7;
}

message ChatMessagePayload {
  string conversation_id      = 1;
  string content_type         = 2;   // "text/plain", "text/markdown", ...
  bytes  content               = 3;
  string reply_to_message_id  = 4;   // optional
  // NOTE: the actual M5c implementation adds message_id, hlc_timestamp, and sender_address —
  // see the M5b/M5c implementation notes below this sketch for why each was added.
}

// Added in M5b — EnvelopeType.DELIVERY_RECEIPT existed above with no corresponding message.
message DeliveryReceiptPayload {
  string conversation_id = 1;
  string message_id      = 2;   // the ChatMessagePayload being acknowledged as delivered
}

// Added in M5b — EnvelopeType.READ_RECEIPT existed above with no corresponding message.
// Watermark-style, not per-message: acks "everything up to and including this hlc_timestamp",
// not one receipt per message read. See the implementation note below for why.
message ReadReceiptPayload {
  string conversation_id         = 1;
  string read_up_to_hlc_timestamp = 2;
}

message GroupOpPayload {
  string conversation_id = 1;
  string op_type          = 2;       // ADD_MEMBER | REMOVE_MEMBER | RENAME | ROTATE_SENDER_KEY
  bytes  op_data           = 3;       // JSON, op-specific
  string actor_peer_id     = 4;
  string hlc_timestamp     = 5;
}

message FileOfferPayload {
  string transfer_id       = 1;
  string conversation_id   = 2;      // see the implementation note below
  string file_name         = 3;
  int64  file_size         = 4;
  string file_hash         = 5;      // SHA-256 of full plaintext
  int32  chunk_size        = 6;
  int32  total_chunks      = 7;
  bytes  wrapped_file_key  = 8;      // per-recipient wrapped AES-256-GCM key
}

// Added in M4b — EnvelopeType.FILE_CHUNK_REQUEST existed above with no corresponding message.
message FileChunkRequestPayload {
  string transfer_id            = 1;
  repeated int32 missing_chunk_indices = 2;  // empty = "I already have everything"
}

message FileChunkPayload {
  string transfer_id  = 1;
  int32  chunk_index  = 2;
  bytes  ciphertext    = 3;          // AES-256-GCM encrypted chunk
  bytes  nonce         = 4;
}
```

**Implementation note (M4b):** `core-filetransfer`'s actual `FileOfferPayload` record omits `conversation_id` and uses a plain (not per-recipient-wrapped) `file_key` — both deliberate, both scoped to M4's single-peer reality rather than this sketch's group-aware one:
- `conversation_id` isn't sent on the wire because there's no group concept yet (M8) — for a 1:1 transfer, "which conversation" is implicit in which session the message arrived over. *(As of M4e, `StorageService.saveConversation` can populate a `conversations` row, but nothing in the file-transfer path calls it — `file_transfers.conversation_id` has no foreign key, unlike `messages`, so this path was never actually blocked by that gap.)* If `conversation_id` needs to become explicit on the wire once groups exist, that's an additive field change, not a breaking one.
- `wrapped_file_key` became a plain `file_key`: the "wrapping" this sketch describes turns out to already happen for free — this whole payload gets encrypted for one specific recipient via their Double Ratchet session before it reaches the wire (§8), so a second, separate wrapping mechanism inside the payload itself would be redundant. See `FileOfferPayload`'s own Javadoc.

**Implementation note (M4c):** a `sender_address` field was added after the first real test caught a genuine bug — see the M4c section of README.md. Not in this sketch at all; the receiver needs a full dialable address for the sender (not just a peer ID) to reply with a chunk request, and the only design that doesn't create a startup ordering problem is the sender reporting its own address inside the (encrypted, authenticated) offer itself.

**Implementation note (M5b):** `core-messaging`'s actual `ChatMessagePayload` record adds two fields not in this sketch — `message_id` and `hlc_timestamp` — moved down from `Envelope`'s shell (fields 4 and 5 above), because that shell has never actually been built anywhere in this project; every milestone so far (M2b/M2c/M4) has carried plaintext bytes with whatever structure the payload itself defines, nothing more. Same situation M4b already resolved the same way for `senderAddress`. `sender_peer_id`/`sender_device_id` did **not** need the same treatment — unlike file transfer's fresh-dial problem, a chat session is already a live, established Double Ratchet session by the time either side sends anything, so `SecureSessionService.decrypt(remote, frame)`'s own `remote` parameter already tells the receiving code who sent it. See `ChatMessagePayload`'s own Javadoc for the full reasoning, including why `conversation_id` — unlike file transfer's — stayed explicit on the wire rather than being left implicit.

**Implementation note (M5c):** M5b's reasoning above about `sender_peer_id` turned out to be half right — correct about the crypto layer, wrong about the network layer. `PeerNetworkService.sendEnvelope`'s own Javadoc: *"a one-shot send: opens a new stream for this call rather than reusing an existing one"* — it always dials a fresh multiaddr, never reuses an inbound connection, so a listener that only knows the sender's peer ID (all `SecureSessionService.decrypt`'s `remote` parameter provides) has no way to physically reply. Exactly M4c's chicken-and-egg bug for `FileOfferPayload`, for the identical reason, found the identical way — reading the network layer's actual contract rather than assuming the crypto layer's session persistence was the whole story. `ChatMessagePayload` gained a `senderAddress` field, mirroring `FileOfferPayload`'s fix exactly, except on every message rather than a one-time offer — chat has no separate handshake payload to concentrate it on, since any given message could be the first of a fresh connection. See the M5c section of README.md for the full account, including an important epistemic caveat that section is explicit about: this was caught by reading `sendEnvelope`'s contract before writing any networking code, not by an actual failed test run the way M4c's identical-shaped bug was.

`.proto` files live in `api-contract` (not yet scaffolded) and will be the single source of truth — both the Java backend and any future non-Java peer implementation compile against the same schema. Until then, `core-filetransfer.wire.FileTransferMessageCodec`'s hand-rolled binary format (documented in that class) is the actual source of truth for what M4 sends on the wire, matching the rest of this project's wire formats (`RelayFrameCodec`, `DiscoveryFrameCodec`, `EncryptedFrameCodec`), none of which use protobuf yet either. `core-messaging.wire.ChatMessageCodec` (M5b) follows the identical hand-rolled convention, as its own independent codec — not a shared one with file transfer; see `ChatWireMessage`'s Javadoc for why unifying the two was considered and deliberately deferred again, past M5.

**Implementation note (M6a):** the deferral above ended here — `node-daemon`'s `ApplicationMessageRouter` now provides the single dispatch boundary a live daemon session needs, peeking the marker byte this table already assigns (`{2,3,4}` chat, `{6,7,8}` file transfer — already disjoint, confirmed before writing anything) and delegating to whichever codec owns it. Neither `ChatMessageCodec` nor `FileTransferMessageCodec` changed; a thin `DispatchedMessage` wrapper in `node-daemon` is the entire addition, not a merge of the two hierarchies. Markers `0`/`1` (`HANDSHAKE_INIT`/`HANDSHAKE_RESPONSE`) are treated as reaching this router in error, not as a real case to route — PQXDH session establishment already happens transparently inside `SecureSessionService.decrypt()`, one layer below, via libsignal's own PreKeySignalMessage/SignalMessage distinction (the `EncryptedFrame` `0x01`/`0x02` marker above); by the time a marker byte reaches the router, a session already exists. `5` (`GROUP_OP`) and `9` (`PRESENCE_PING`) are reserved-not-unknown, rejected with a message saying so rather than a generic error.

### Custom protocol wire formats (already implemented)

In addition to the protobuf application layer above, three custom libp2p protocol wire formats are implemented directly in `core-network` as simple binary codecs:

| Protocol | ID | Frame format |
|:---|:---|:---|
| Envelope | `/p2p-chat/envelope/0.1.0` | Raw byte payload (no framing — the libp2p stream boundary is the frame) |
| Relay | `/p2p-chat/relay/0.1.0` | `[1 byte: 0x01=FORWARD / 0x02=DELIVER][4 bytes: peer ID length][peer ID UTF-8][4 bytes: payload length][payload bytes]` |
| Discovery | `/p2p-chat/discovery/0.1.0` | `[1 byte: message type][4 bytes: peer ID length][peer ID UTF-8][4 bytes: payload length][payload bytes]` |

The `EncryptedFrame` format carried inside Envelope payloads: `[1 byte: 0x01=PreKey / 0x02=Whisper][remaining bytes: serialized libsignal ciphertext]`.

---

## 7. Local frontend ↔ backend API (JSON-RPC 2.0 over WebSocket)

The Electron app connects to `ws://127.0.0.1:<port>/v1` on launch. Requests/responses are JSON-RPC 2.0; server-initiated events use the same socket with a reserved `event.*` method namespace instead of an `id`.

**Methods**

| Method | Params | Returns |
|---|---|---|
| `identity.create` | `{ displayName }` | `Identity` |
| `identity.get` | `{}` | `Identity` |
| `contacts.add` | `{ inviteCode }` | `Contact` |
| `contacts.list` | `{}` | `Contact[]` |
| `conversations.list` | `{}` | `Conversation[]` |
| `conversations.createGroup` | `{ name, memberIds[] }` | `Conversation` |
| `messages.send` | `{ conversationId, contentType, content }` | `{ messageId }` |
| `messages.history` | `{ conversationId, cursor, limit }` | `Message[]` |
| `files.send` | `{ conversationId, filePath }` | `{ transferId }` |
| `files.accept` | `{ transferId, savePath }` | `{}` |
| `files.cancel` | `{ transferId }` | `{}` |
| `network.status` | `{}` | `NetworkStatus` |
| `network.connectedPeers` | `{}` | `PeerInfo[]` |

**Example request/response**

```json
// → request
{ "jsonrpc": "2.0", "id": 17, "method": "messages.send",
  "params": { "conversationId": "c_9f2a", "contentType": "text/plain", "content": "hey" } }

// ← response
{ "jsonrpc": "2.0", "id": 17, "result": { "messageId": "m_7bd1" } }
```

**Push events** (no `id`, server → client only)

```json
{ "jsonrpc": "2.0", "method": "event.message.received",
  "params": { "message": { "messageId": "m_7bd2", "conversationId": "c_9f2a",
              "senderPeerId": "12D3Koo...", "contentType": "text/plain",
              "content": "hey back", "hlcTimestamp": "..." } } }

{ "jsonrpc": "2.0", "method": "event.transfer.progress",
  "params": { "transferId": "t_1c88", "chunksReceived": 42, "totalChunks": 500,
              "state": "IN_PROGRESS" } }

{ "jsonrpc": "2.0", "method": "event.network.statusChanged",
  "params": { "status": "RELAYED", "connectedPeerCount": 3 } }
```

This is the contract the frontend team can build the entire UI against without waiting on the P2P internals — mock this JSON-RPC server early and the UI work is fully decoupled from the network work.

---

## 8. Security architecture

- **Identity keys**: Ed25519 for the long-term network identity keypair (used to derive the libp2p `PeerId` via `IdentityService.rawPrivateKeySeed()`); Curve25519 (X25519-family, via libsignal's `IdentityKeyPair`) for Signal Protocol session keys. These are **two separate identities by design** — there is no safe conversion from Ed25519 to X25519 in this context, so `SignalIdentityVault` persists a distinct keypair used only for cryptographic sessions.
- **Session establishment**: **PQXDH** (Post-Quantum Extended Triple Diffie-Hellman) — identity key + signed prekey + one-time prekey + **Kyber-1024** post-quantum prekey, allowing session setup even if the recipient is offline at the time. The pre-key bundle is serialized by `PreKeyBundleCodec` (length-prefixed binary fields, matching the 11-arg `PreKeyBundle` constructor order, since libsignal's `PreKeyBundle` is a JNI-backed native object with no built-in serialization).
- **Per-message encryption**: Double Ratchet, giving forward secrecy (compromise of a later key does not expose earlier messages) and post-compromise security (session self-heals after a compromise, given continued communication). Wire-framed as an `EncryptedFrame` record (a 1-byte type marker + raw libsignal ciphertext bytes), encoded by `EncryptedFrameCodec`.
- **Group encryption**: sender-key scheme — each member generates a symmetric sender key, distributes it to every other member over their existing pairwise Double Ratchet sessions, and rotates it whenever membership changes (so a removed member cannot decrypt future messages).
- **File encryption**: each file gets a fresh random AES-256-GCM key; the file is encrypted once, and that *key* (not the file) is wrapped individually per-recipient via their session — so file ciphertext can be shared byte-for-byte across a swarm while only intended recipients can decrypt it.
- **At-rest storage**: the local key vault (identity private key, session state) must be encrypted with a key derived from an OS keychain entry (`macOS Keychain` / `Windows DPAPI` / `Linux Secret Service`) or a user passphrase as fallback. The current implementation stores key files as plaintext on disk — this is a known gap explicitly deferred from M0, and must be closed before this application is used with a real identity outside local development.
- **Verification**: expose a "safety number" / QR-code comparison flow so users can verify a contact's identity key out-of-band — this is what defends against a malicious relay or bootstrap node attempting a MITM, since those nodes never see identity keys, only routing metadata.

**Library choice:** `org.signal:libsignal-client` (v0.94.0+) — the actively maintained Signal Protocol library, the same code Signal's own applications run. It is **AGPL-3.0 licensed**. Using it during development without distributing the application carries no practical obligation. If this project is ever distributed publicly while depending on it, AGPL's terms apply from that point. This must be revisited before any public release. The library has a native Rust core with JNI bindings — a deliberate exception to the "pure Java core" principle, scoped to `core-crypto` behind its own interface, so it remains swappable.

---

## 9. Storage schema (SQLite, `core-storage`)

**Implemented in M3d**, exactly as below except one correction: `sender_device_id` was missing from this table in earlier drafts of this document despite §4's `Message` record and §13 both describing every message as carrying one — added below and in the real `V001__init.sql`, which is the source of truth if this text and that file ever disagree.

```sql
CREATE TABLE identities (
  peer_id           TEXT PRIMARY KEY,
  display_name      TEXT NOT NULL,
  identity_pubkey   BLOB NOT NULL,
  created_at        INTEGER NOT NULL
);

CREATE TABLE contacts (
  peer_id           TEXT PRIMARY KEY,
  display_name      TEXT,
  verified          INTEGER DEFAULT 0,
  added_at          INTEGER NOT NULL
);

CREATE TABLE conversations (
  conversation_id   TEXT PRIMARY KEY,
  type              TEXT CHECK(type IN ('DIRECT','GROUP')) NOT NULL,
  name              TEXT,
  created_at        INTEGER NOT NULL
);

CREATE TABLE conversation_members (
  conversation_id   TEXT NOT NULL REFERENCES conversations(conversation_id),
  peer_id           TEXT NOT NULL,
  role              TEXT CHECK(role IN ('MEMBER','ADMIN')) DEFAULT 'MEMBER',
  joined_at         INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, peer_id)
);

CREATE TABLE messages (
  message_id        TEXT PRIMARY KEY,
  conversation_id   TEXT NOT NULL REFERENCES conversations(conversation_id),
  sender_peer_id    TEXT NOT NULL,
  sender_device_id  TEXT NOT NULL DEFAULT '0', -- see the correction note above §9
  hlc_timestamp     TEXT NOT NULL,          -- sortable string, see §11
  content_type      TEXT NOT NULL,
  plaintext_cache   TEXT,                    -- decrypted, local-only; never leaves the device
  delivery_state    TEXT CHECK(delivery_state IN ('SENDING','SENT','DELIVERED','READ','FAILED')),
  created_at        INTEGER NOT NULL
);
CREATE INDEX idx_messages_conv_time ON messages(conversation_id, hlc_timestamp);

CREATE TABLE file_transfers (
  transfer_id       TEXT PRIMARY KEY,
  conversation_id   TEXT NOT NULL,
  file_name         TEXT NOT NULL,
  file_size         INTEGER NOT NULL,
  file_hash         TEXT NOT NULL,
  chunk_size        INTEGER NOT NULL,
  total_chunks      INTEGER NOT NULL,
  state             TEXT CHECK(state IN ('OFFERED','ACCEPTED','IN_PROGRESS','PAUSED','COMPLETED','FAILED','CANCELLED')),
  local_path        TEXT,
  created_at        INTEGER NOT NULL
);

CREATE TABLE file_chunk_state (
  transfer_id       TEXT NOT NULL REFERENCES file_transfers(transfer_id),
  chunk_index       INTEGER NOT NULL,
  received          INTEGER DEFAULT 0,
  PRIMARY KEY (transfer_id, chunk_index)
);

CREATE TABLE crdt_ops_log (
  op_id             TEXT PRIMARY KEY,
  conversation_id   TEXT NOT NULL,
  actor_peer_id     TEXT NOT NULL,
  op_type           TEXT NOT NULL,
  op_payload        TEXT NOT NULL,           -- JSON
  hlc_timestamp     TEXT NOT NULL
);
```

Migrations are managed with a versioned-script runner (`V001__init.sql`, `V002__...sql`) applied on daemon startup — never hand-edit the schema in place once shipped.

**M6e-1 added `V002__signal_store.sql`** — persistent storage for Signal Protocol sessions, pre-keys, and remote identity keys, backing a new `SqliteSignalProtocolStore` (`node-daemon`, deliberately not `core-storage` — that module gains no `libsignal-client` dependency by this):

```sql
CREATE TABLE signal_sessions (
  address           TEXT PRIMARY KEY,   -- "<SignalProtocolAddress name>.<deviceId>"
  session_record    BLOB NOT NULL,      -- SessionRecord.serialize()
  updated_at        INTEGER NOT NULL
);

CREATE TABLE signal_pre_keys (
  prekey_id         INTEGER PRIMARY KEY,
  record            BLOB NOT NULL       -- PreKeyRecord.serialize()
);

CREATE TABLE signal_signed_pre_keys (
  signed_prekey_id  INTEGER PRIMARY KEY,
  record            BLOB NOT NULL       -- SignedPreKeyRecord.serialize()
);

CREATE TABLE signal_kyber_pre_keys (
  kyber_prekey_id   INTEGER PRIMARY KEY,
  record            BLOB NOT NULL,      -- KyberPreKeyRecord.serialize()
  used              INTEGER NOT NULL DEFAULT 0   -- flagged, not deleted -- see note below
);

CREATE TABLE signal_identities (
  address           TEXT PRIMARY KEY,
  identity_key      BLOB NOT NULL       -- IdentityKey.serialize()
);
```

Every column is an opaque blob — `.serialize()` in, the matching `byte[]` constructor out, never inspected, per Signal's own guidance for store implementations. Deliberately *not* in this table: this node's own local identity (`IdentityKeyPair` + registration ID), which stays exactly where `SignalIdentityVault` (M2a) already puts it, in `dataDir/signal-identity.key`/`.reg` — only genuinely dynamic runtime state lives here. `signal_kyber_pre_keys.used` is a deliberate departure from the `DELETE`-on-consume approach `signal_pre_keys` uses: `removePreKey` really does `DELETE FROM signal_pre_keys` — the actual point of that table existing at all, since forward secrecy across a restart depends on a consumed one-time prekey being physically gone — but Kyber prekeys in PQXDH are last-resort/reusable rather than strictly single-use, and `markKyberPreKeyUsed` is a genuinely different SPI method from `removePreKey` for exactly that reason.

**`V003__kyber_base_key_replay.sql`** followed once a real build against `libsignal-client` 0.94.0 showed `markKyberPreKeyUsed`'s actual signature takes `(kyberPreKeyId, signedPreKeyId, ECPublicKey baseKey)` and can throw `ReusedBaseKeyException` — real base-key replay protection, not the bare consumption flag `V002` assumed:

```sql
CREATE TABLE signal_kyber_base_keys_seen (
  kyber_prekey_id   INTEGER NOT NULL,
  signed_prekey_id  INTEGER NOT NULL,
  base_key          BLOB NOT NULL,      -- ECPublicKey.serialize()
  PRIMARY KEY (kyber_prekey_id, signed_prekey_id, base_key)
);
```

A new migration, not an edit to `V002` — that table is already applied against real data by the time this was needed, and `V002`'s own comment already states the "never hand-edit the schema in place once shipped" rule this follows. The composite `PRIMARY KEY` does two jobs at once: persists the replay check across a restart (the first draft tracked seen base keys in a plain in-memory `Map`, silently losing that protection every time the daemon restarted — the exact problem this whole section's schema exists to solve, reappearing inside itself), and compares by actual key bytes rather than by Java object identity (the in-memory draft used `Set<ECPublicKey>`, which relies on `ECPublicKey` having value-based `equals()`/`hashCode()` — nothing confirms it does, and SQL's own `PRIMARY KEY` uniqueness sidesteps the question entirely).

---

## 10. Connectivity: discovery, NAT traversal, relay

> **Amendment (verified against the live `libp2p/jvm-libp2p` repository):** the original version of this section assumed `jvm-libp2p` ships a working Kademlia DHT and mature NAT traversal out of the box, the way go-libp2p does. It does not — the JVM implementation deliberately scoped those out. Checked directly against the project's own component-status table:
> - **Kademlia DHT (peer routing) — not implemented.**
> - **Hole-punching — not implemented.**
> - **Bootstrap/rendezvous discovery — not implemented** (mDNS discovery for LAN is available, in beta).
> - **AutoNAT and Circuit Relay v2 — exist, but are explicitly beta/prototype, not production-tested.**
> - What *is* production-tested: TCP transport, Noise encryption, mplex stream multiplexing, and the `ping` and `identify` protocols.
>
> This does not block M1 — direct LAN connection only needs the production-tested pieces. It does mean the M3 discovery/NAT plan is revised from an original DHT-based design, and this is a good example of exactly the kind of thing this spec exists to catch before it becomes a mid-build surprise.

- **Transport**: libp2p over TCP (via `jvm-libp2p` v1.3.4). QUIC is beta upstream, so it is a later swap-in, not a v1 dependency.
- **Discovery (implemented in M3c)**: a custom publish/lookup protocol, `/p2p-chat/discovery/0.1.0`, co-located with `relay-server`. Peers publish their reachable multiaddr(s) to the discovery server; other peers query by peer ID. This is structurally simpler than integrating a DHT, and reuses the `relay-server` infrastructure already established for relaying. On a LAN, jvm-libp2p's mDNS discovery is also available and may be enabled as a complement.
- **NAT traversal (implemented in M3a/M3b)**: (1) direct connection when both sides have a reachable address; (2) fall back to relaying application traffic through a `relay-server` node using a custom relay protocol (`/p2p-chat/relay/0.1.0`) that we fully control. The relay also doubles as a store-and-forward point for a peer that is currently offline. AutoNAT / Circuit-Relay-v2 / hole-punching remain on the radar as potential upgrades once they mature upstream, but the plan no longer depends on them working.
- **Connectivity status is always surfaced.** `ConnectivityStatus` (DIRECT / RELAYED / UNREACHABLE) is returned by `ConnectionStrategy.send()` and will be exposed through `PeerNetworkService.connectivityStatus()` per peer once the full daemon is wired. The UI must show this — "connecting..." / "relayed" states map directly onto it.
- **Self-address discovery is still an open gap, found by M5c.** `network.listenAddresses()` can return a wildcard bind address (observed: `/ip6/::/tcp/<port>/...` on Windows) rather than a concrete, dialable one — not a problem this section's design anticipated. M5c's `ChatListenerMain`/`ChatSenderMain` resolve this to loopback, which is correct only because that milestone's demo runs both processes on one machine; it is explicitly not a fix for two different physical machines (see `firstDialableAddress`'s own Javadoc). Real self-address discovery — a real LAN IP, or STUN-style external address discovery — is unbuilt and unresolved; worth resolving properly before M6/M7 need peers on different machines to actually reach each other.

> **Amendment (M5d):** the same-machine limitation above is now narrower, though the underlying gap this bullet describes is not closed. `firstDialableAddress` was promoted to `core-network`'s `DialableAddressResolver` and now performs real local network-interface enumeration, resolving a wildcard bind to this machine's actual LAN IP rather than always loopback — same-LAN two-machine chat/file transfer should now work without manual address substitution. Still explicitly unsolved: (1) reachability across different networks/NAT — `ConnectionStrategy`'s direct-first/relay-fallback logic (below) is proven in isolation but not yet wired into the chat/file send paths, which still call `sendEnvelope` directly; and (2) real external/public address discovery (STUN or similar) for a node with no usable LAN address, which remains fully unbuilt. Both are explicitly scoped to M6 — see the M5d section of README.md.

---

## 11. Group messaging & CRDT design

Group *membership and metadata* (who is in the group, admin list, group name) is modeled as an operation-based CRDT (an OR-Set for membership) — every membership change is a signed, timestamped operation broadcast to all members, and any member can replay the op log to arrive at the same state regardless of the order operations were received in. This is what makes group state convergent without a central sequencer.

**Why hybrid logical clocks (HLC), not wall-clock timestamps:** peer clocks drift and cannot be trusted for ordering across devices. An HLC combines a physical clock with a logical counter, giving a value that is both roughly time-ordered *and* strictly monotonic per-causal-chain — this is what `hlc_timestamp` is, everywhere it appears in this spec. ~~Use an existing HLC implementation rather than hand-rolling one.~~ **Superseded in M5a:** searched properly before writing any code — there is no maintained, published Java HLC library to depend on (see the M5a section of README.md for what was actually found). Implemented directly against Kulkarni, Demirbas, Madeppa, Avva, Leone, *"Logical Physical Clocks"* (OPODIS 2014 / SUNY Buffalo Tech Report 2014-04), Figure 4, in `core-messaging` (`HlcTimestamp` + `HybridLogicalClock`), verified by compiling and running it directly rather than hand-traced — including deliberately breaking the thread-safety mechanism to confirm the concurrency test actually catches the regression it claims to.

Group *message* encryption uses the sender-key scheme described in §8, not per-member Double Ratchet for every message (that does not scale past a handful of members).

---

## 12. File transfer protocol

1. Sender computes `file_hash` (SHA-256 of plaintext) and chunks the file (default 256 KB chunks — tune later).
2. Sender generates a random AES-256-GCM file key, encrypts each chunk, and sends a `FileOfferPayload` (with the file key wrapped per-recipient) over the existing encrypted session.
3. Recipient responds with `FILE_CHUNK_REQUEST`s for missing chunks (tracked in `file_chunk_state`), enabling resume after disconnect — a fresh request just skips chunks already marked `received`.
4. For group file shares, chunks are content-addressed by `file_hash` + `chunk_index`, so multiple recipients can serve chunks to each other swarm-style (the piece-selection and swarm logic from the `bt` library is reused here, even though the encryption/session layer is custom).
5. On completion, recipient verifies the full-file SHA-256 against `file_hash` before marking `COMPLETED` — this catches any corruption or tampering regardless of transport path (direct or relayed).

---

## 13. Multi-device & sync (v1 scope + the hook for later)

**v1: one device per identity.** This sidesteps the single hardest problem in decentralized messaging. It is a real limitation and should be stated as such in the product, not hidden.

**The hook already in place:** every `Envelope` carries `sender_device_id` (always `"0"` in v1), and `PeerId` is separate from `DeviceId` in the domain model from the start. When multi-device linking is built later (a second device generates its own keypair, and an existing device signs a "linked device" attestation — the same pattern Signal uses), the wire protocol does not change shape; the code only needs to populate a field that already exists and add logic that reads it, plus a new linking flow.

---

## 14. Versioning & extensibility strategy

- `Envelope.protocol_version` — bump only on breaking wire changes; additive fields do not require a bump (protobuf handles unknown-field skipping natively).
- Local API is path-namespaced (`/v1`); a `/v2` can run alongside it during a frontend migration.
- Handshake exchanges a capability list (e.g. `["group-v1", "file-transfer-v1"]`) so two nodes can negotiate down to shared functionality instead of failing outright when one side is older.
- Every new `EnvelopeType` or JSON-RPC method is additive by construction — nothing in this design requires renumbering or removing existing fields to extend it.

---

## 15. Error handling & edge case checklist

Concrete failure modes to design against now, not discover later:

- **Peer goes offline mid-file-transfer** → chunk-level resume (already covered in §12); transfer state persists in SQLite, not just in memory.
- **Duplicate message delivery** (e.g. via both a direct reconnect and a queued relay copy) → dedup on `message_id` at the storage layer before it ever reaches the UI.
- **Clock skew across peers** → HLC instead of wall-clock for all ordering (§11).
- **Relay node unavailable** → client retries other known relays/bootstrap nodes, and surfaces `UNREACHABLE` connectivity honestly rather than hanging silently.
- **Key compromise / device loss** → identity key rotation path must exist even in v1 (rotating signed prekeys via `rotateSignedPreKey()`), with contacts re-verifying via safety number after a rotation. *(M6e-1 implemented the storage-layer mechanism this depends on: `SqliteSignalProtocolStore.saveIdentity` distinguishes "first time seeing this address" from "a genuinely different identity replacing a previously-trusted one" and reports which happened; `isTrustedIdentity` refuses the latter case outright rather than silently accepting a swapped key. The UI flow itself — surfacing that change to the user, prompting safety-number re-verification — is not built; this is only the signal a future UI would consume.)*
- **Group membership race** (two admins remove different members simultaneously) → resolved automatically by the CRDT merge (§11) rather than needing a "last writer wins" special case.
- **Malicious or spam peer** → local block-list enforced client-side (no central authority to appeal to, so this must be usable and prominent in the UI from v1).
- **Discovery record spoofing** → currently no signature on published records (low risk while only network addresses are published — worst case is a failed connection attempt). Becomes security-critical once PreKeyBundle discovery is added; signatures on published records must be added at that point.

---

## 16. Technology stack summary

| Concern | Choice |
|---|---|
| P2P networking | `jvm-libp2p` v1.3.4 (TCP transport, Noise encryption, mplex, ping/identify — production-tested pieces only; see §10 amendment). Not on Maven Central — hosted on Cloudsmith, JitPack, and Consensys artifact repos; all three are added to `repositories {}` in the root `build.gradle.kts`. |
| Encryption | `org.signal:libsignal-client` v0.94.0+ (PQXDH + Double Ratchet — the real, current Signal Protocol library; AGPL-3.0, see §8) + standard JDK `java.security` (Ed25519 keypair generation, SHA-256) |
| File swarm transfer | `bt` (BitTorrent-protocol Java library), adapted for encrypted content-addressed chunks |
| Local persistence | SQLite (via JDBC), hand-written versioned migrations |
| Local API transport | WebSocket + JSON-RPC 2.0 |
| Desktop UI | Electron + React/TypeScript |
| Mobile UI | Kotlin + Jetpack Compose, sharing `core-*` modules directly (same JVM) |
| Build system | Gradle multi-module (Kotlin DSL), JDK 21 toolchain |

---

## 17. Build order & milestone log

1. **M0** ✅ — Identity + local key vault. Ed25519 keypair persisted to disk; peer ID derived as SHA-256 of the public key (hex-encoded placeholder). Daemon skeleton prints identity info and exits.

2. **M1** ✅ — Direct P2P connectivity on a LAN using jvm-libp2p's built-in `ping` protocol. Two independent libp2p hosts over a real TCP socket, five ping round-trips confirmed.

3. **M1.5** ✅ — Persistent peer identity binding. `IdentityService.rawPrivateKeySeed()` (via JDK `EdECPrivateKeySpec`) extracts the 32-byte Ed25519 seed and feeds it into `HostBuilder.builderModifier()`, making the libp2p peer ID stable across restarts. Both `runListener` and `runPinger` now load the persisted identity first.

4. **M2a** ✅ — PQXDH session establishment + Double Ratchet encryption proven in isolation with `libsignal-client`. Two in-memory identities (Alice and Bob), real handshake, real encrypt/decrypt round-trip, confirmed `M2a CONFIRMED` output.

5. **M2b** ✅ — Custom libp2p protocol `/p2p-chat/envelope/0.1.0` registered alongside `ping`. Arbitrary application bytes transferred between real peers. `EnvelopeProtocol` / `EnvelopeBinding` / `EnvelopeController` / `OnEnvelopeMessage` introduced in `core-network`.

6. **M2c** ✅ — Identity (M1.5) + transport (M1/M2b) + encryption (M2a) proven working together. `SignalIdentityVault`, `PreKeyBundleFactory`, `PreKeyBundleCodec`, `EncryptedFrameCodec`, and `SecureSessionService` introduced in `core-crypto`. Bundle exchange is manual/out-of-band by design; a reply path and bundle discovery are deferred to M3.

   **M2 complete.** Identity, real transport, and real E2E encryption are proven working together, not just individually.

7. **M3a** ✅ — Relay mechanism proven in isolation. New `relay-server` module. `RelayProtocol` / `RelayBinding` / `RelayController` / `RelayEventHandler` / `RelayFrame` / `RelayFrameCodec` introduced in `core-network`. `RelayRegistry` in `relay-server`. Three-process test: relay + registering peer + forwarding peer, message delivered end-to-end through the relay.

8. **M3b** ✅ — Direct-first, relay-as-fallback connection strategy. `ConnectivityStatus` (DIRECT / RELAYED / UNREACHABLE) and `ConnectionStrategy` introduced in `core-network`. `sendEnvelope` gained a timeout-aware 3-arg overload. Confirmed via real two-path testing: direct path succeeds against a real listener; a closed port correctly triggers fallback to a real relay.

9. **M3c** ✅ — Real peer discovery via `/p2p-chat/discovery/0.1.0`, co-located with `relay-server`. `DiscoveryProtocol` / `DiscoveryBinding` / `DiscoveryController` / `DiscoveryRequestHandler` / `DiscoveryFrame` / `DiscoveryFrameCodec` / `DiscoveryMessageType` / `DiscoveryLookupResult` introduced in `core-network`. `DiscoveryRegistry` in `relay-server`. A TOCTOU concurrency race in the `Initiator`'s pending-lookup tracking was identified and fixed with `AtomicReference.compareAndSet` before shipping. Replaces manual multiaddr hand-carrying used from M1 through M3b.

   **M3 complete.** Relay, direct-first fallback, and peer discovery are proven working.

10. **M3d** ✅ — Foundation pass ahead of M4, not a user-visible milestone. `core-model` (new) unified `PeerId`/`DeviceId` as one Java type across module boundaries, replacing a mix of raw `String` and `io.libp2p.core.PeerId`; `core-network`'s `jvm-libp2p` dependency correctly reverted from `api` to `implementation` once it stopped leaking through the public API. `core-storage` (new) implemented the full schema from §9, a versioned `MigrationRunner`, and `StorageService` exactly matching §5's five-method sketch — done now because "resumable" file transfer only means something if chunk state survives a restart. One spec correction made along the way: `messages` was missing a `sender_device_id` column despite §4/§13 both describing every message as carrying one.

11. **M4a** ✅ — First piece of M4. Chunking + per-chunk AES-256-GCM encryption, proven in isolation (no networking, no storage) — same pattern M2a used for session crypto. New `core-filetransfer` module, zero dependencies (pure JDK). Implements §12 steps 1, 2, and 5. Compiled and run directly rather than hand-traced, since this module has no external dependencies.

12. **M4b** ✅ — Second piece of M4. Wire payload codecs for `FileOfferPayload`/`FileChunkRequestPayload`/`FileChunkPayload`, proven in isolation (21 round-trip/edge-case checks, also compiled and run directly). Filled a real gap in this document — `FileChunkRequestPayload` was never defined despite `EnvelopeType.FILE_CHUNK_REQUEST` existing. Settled the message-framing design question here rather than guessing: a narrow, file-transfer-specific type discriminator (reusing this document's own `EnvelopeType` numeric values) instead of the not-yet-built general `Envelope` layer, since file transfer's own correctness doesn't need `message_id`/`hlc_timestamp` the way chat messages will.

13. **M4c** ✅ — Wire M4a/M4b to real peers: a `FileSenderMain`/`FileReceiverMain` pair over the existing `EnvelopeProtocol`/`SecureSessionService` pipeline, same shape as M2c but genuinely bidirectional for the first time — the receiver has to reply on its own initiative, not just decrypt and stop. The two load-bearing assumptions verified against the real source before writing this (session-state persistence across encrypt/decrypt calls; `sendEnvelope`'s symmetry) both held up on the first real test — the PQXDH session and offer decryption worked correctly. What the first test *did* catch was a real design bug, not a crypto/networking one: the receiver required the sender's address as a startup argument, which is unknowable at the point the documented workflow says to start it. Fixed by having the sender report its own address inside the encrypted offer (`FileOfferPayload.sender_address`, see §6) instead. Still no resume.

14. **M4d** ✅ — Chunk-level resume via `core-storage`: `StorageService` gained `markChunkReceived`/`missingChunks`, and `FileReceiverMain` now writes each chunk directly to its correct byte offset on disk as it arrives (not in memory), so a restart has real bytes to resume into. Compiled and run directly against a real SQLite database (via a jar fetched from GitHub Releases rather than Maven Central, which this sandbox can't reach) with a genuinely simulated process restart — the first storage-touching milestone piece that wasn't hand-traced. That direct testing caught a real bug immediately: `file_chunk_state`'s foreign key on `file_transfers` means `markChunkReceived` requires `saveFileMetadata` to run first, and `saveFileMetadata` had to become an upsert since a resumed transfer legitimately calls it again for an existing `transferId`. **M4 (single-peer, chunked, encrypted, resumable file transfer) is now complete, M4a through M4d.**

    **Known issue found, then fixed in M4e:** the same investigation found that `messages` has an identical foreign-key shape (`conversation_id REFERENCES conversations`) with no `StorageService` method able to create a `conversations` row — and testing `saveMessage` under those conditions fails the same way `markChunkReceived` did, in this sandbox. M3d's `StorageDemoMain` exercised the exact same conditions successfully, on real hardware, so at the time this looked like an unresolved discrepancy between that result and this one. Flagged rather than silently patched.

15. **M4e** ✅ — Fixed the issue above, and explained the discrepancy rather than just patching around it: `StorageDemoMain` and `SqliteStorageServiceTest` had both been creating their `conversations` row with a raw, hand-written JDBC `INSERT`, bypassing `StorageService` entirely — which satisfies SQLite's foreign key exactly as well as a row created through the service layer, so both were quietly working around the gap rather than exercising it. There was never a real disagreement between sandboxes. New `Conversation` model (`core-storage.model`) and `StorageService.saveConversation`, upsert semantics matching `saveFileMetadata`'s existing pattern, deliberately narrower than §4's full `Conversation` sketch (no `members` — see that model's Javadoc). Compiled and run directly against a real SQLite database, same as M4d: confirmed the exact FK violation message, confirmed the fix resolves it, and confirmed a raw-SQL-created row unblocks `saveMessage` exactly as well as one created via the new method — proving the masking theory rather than asserting it. `StorageDemoMain` and `SqliteStorageServiceTest` updated to use the real method; two new regression tests added. See the M4e section of README.md for the full narrative.

    **Milestone reordering from here (see README.md for the full rationale):** the original plan bundled "group chat" and "Electron UI" into single M5/M6 milestones. `core-messaging` and `core-groups` were always two separate modules per §3 — the old table just hadn't split them. Group chat (M8) is now deliberately sequenced *after* a working 1:1 product (M5–M7) rather than right after file transfer: it's the hardest, least-proven remaining piece (no HLC implementation exists anywhere yet despite being referenced throughout this document, and OR-Set CRDT merge semantics aren't something to hand-trace with confidence the way M1–M4 were), and it lands additively on top of M5's work regardless — `conversations.type` is already `DIRECT | GROUP`, `conversations.createGroup` is already in the §7 RPC table, `GROUP_OP` is already a numbered `EnvelopeType` (§6), and sender-key group encryption rides on top of the pairwise Double Ratchet sessions M2/M5 already establish rather than replacing them.

16. **M5** ✅ — 1:1 messaging (`core-messaging`, new module): real send/receive replacing the hardcoded demo strings every prior milestone has used, delivery/read receipts, HLC-based ordering, message-id dedup. First real caller of `saveMessage`/`saveConversation`.
    - **M5a** ✅ — `HlcTimestamp` + `HybridLogicalClock`, proven in isolation. See the M5a section of README.md and the correction above (§11) — the "use an existing implementation" line didn't survive contact with an actual search for one.
    - **M5b** ✅ — Chat wire payloads (`ChatMessagePayload`, plus `DeliveryReceiptPayload`/`ReadReceiptPayload`, filling a §6 gap of the same shape M4b found for `FileChunkRequestPayload`), proven in isolation. See the M5b section of README.md.
    - **M5c** ✅ (verified on real hardware — two real bugs found and fixed: a wildcard-bind-address issue, and a Netty event-loop deadlock now documented directly in `PeerNetworkService`'s own Javadoc) — Wired between two real peers: real bidirectional 1:1 chat over `SecureSessionService`, persisted via `saveConversation`/`saveMessage`. `ChatListenerMain`/`ChatSenderMain` depend on `core-network`/`core-crypto` (jvm-libp2p, libsignal-client), unreachable in this sandbox — same constraint M0–M4c originally had. See the M5c section of README.md, including the `senderAddress` correction to M5b found while designing this.
    - **M5d** ✅ (verified on real hardware) — Message-id dedup on receive + delivery/read receipt state transitions in storage. `StorageService` gained `hasMessage`/`updateDeliveryState`/`markMessagesReadUpTo`; `ChatListenerMain`/`ChatSenderMain` check the first before persisting, send the latter two automatically (delivery) or on an opt-in `markread` flag (read, simulating a real UI action that doesn't exist until M7). See the M5d section of README.md.

    **M5 complete, M5a through M5d.**

17. **M5e** ✅ (verified on real hardware) — Pre-M6 cleanup pass, from a peer-authored checklist reviewed against the actual source before acting on it. Four real, checkable items: (1) the same Netty-event-loop deadlock M5c found in chat, present and fixed identically in `FileReceiverMain`/`FileSenderMain`'s callback-triggered sends, never backported since M4c predates that discovery; (2) unknown-marker validation and length-prefix bounds checks audited and hardened across all 6 wire codecs in the project, not just the 2 originally flagged — `EncryptedFrameCodec`/`RelayFrameCodec` had the actual silent-misdecode bug, the rest were missing allocation bounds checks; (3) `HybridLogicalClock.checkDrift`, a strictly additive opt-in guard against an implausibly-future remote timestamp — closes a gap that class's own Javadoc had flagged as deferred since M5a, without touching `update`'s own algorithm at all; (4) a permanent `-Pduplicatesend` test flag proving M5d's dedup path live, not just via its storage-layer unit test. A larger set of M6-scoped design questions (canonical peer identity, shared chat/file dispatch, one outbound send path, discovery record shape, pre-key bundle lifecycle, storage transaction boundaries, a daemon error vocabulary) were deliberately identified and left open rather than answered here — see "Open M6 design decisions" in README.md.

18. **M6** 🔄 — `node-daemon` composition root + local JSON-RPC/WebSocket API (§7), 1:1 scope. First long-running process holding multiple simultaneous peer sessions — everything through M5e is one-shot CLI demos. Also where M3c's discovery replaces manually hand-carrying a bundle file/multiaddr between terminals. In progress, broken into sub-milestones (M6a–M6h) rather than done as one pass, following the same discipline M2–M5 used:
    - **M6a** ✅ (sandbox-verified — pending confirmation on real hardware) — Shared decrypted-message dispatch: `ApplicationMessageRouter` + `DispatchedMessage` in `node-daemon`. See the M6a section of README.md.
    - **M6e-1** ✅ (fully confirmed — 39/39 real, executed against the real `libsignal-client` 0.94.0 jar and real SQLite, including a fresh non-cached run) — Persistent Signal Protocol session store (`SqliteSignalProtocolStore`, `SynchronizedSignalProtocolStore`, `V002__signal_store.sql`/`V003__kyber_base_key_replay.sql` — see §9). Built and proven in isolation ahead of M6b–M6d despite the letter ordering: a deliberate reordering, since this is the highest-blast-radius single piece in the whole M6 roadmap. Two real signature mismatches surfaced on the first real build (`saveIdentity`'s `IdentityChange` return, `markKyberPreKeyUsed`'s 3-arg/`ReusedBaseKeyException` signature); fixing the second caught a genuine persistence bug the sandbox hand-trace had introduced. A later real build also caught a test-fixture issue — `SessionRecord`'s real constructor parses straight into a protobuf `SessionStructure`, rejecting arbitrary byte literals — fixed by generating real ratchet state via an actual handshake instead. See the M6e-1 section of README.md.
    - **M6b** ✅ (real production code compiled and run directly in the sandbox that produced it — no jvm-libp2p stubs needed, unlike M6e-1) — One outbound send path (`OutboundMessageService`, `node-daemon`), wrapping the already-complete `ConnectionStrategy` (M3b) for async execution off Netty callback threads plus an overall timeout. Confirmed `ConnectionStrategy`/`ConnectivityStatus`/`PeerNetworkService` have zero jvm-libp2p/Netty imports before writing anything, which is what made a real (not hand-traced) test double and real executed coverage possible. One real bug caught the same way as everywhere else in M6 so far — not by review, by running it: `CompletableFuture.supplyAsync(...)` on an already-closed executor throws synchronously, before any future exists for `.exceptionally(...)` to catch, breaking this class's own "always resolves to a status" guarantee in that one edge case. Fixed. See the M6b section of README.md.
    - Remaining: M6c (hand-rolled JSON value model), M6d (hand-rolled WebSocket transport), M6e-2 (wiring the M6e-1 store and M6b's send path into a live multi-session daemon core), M6f (signed discovery records), M6g (JSON-RPC method surface + push events), M6h (`DaemonMain` composition root).

19. **M7** 🔜 — Electron frontend wired against the API — 1:1 chat + file transfer UI.

20. **M8** 🔜 — Group chat: CRDT membership + sender-key group encryption (`core-groups`, new module). See §11.

21. **M9** 🔜 — Packaging/distribution; Android port of the core.

22. **Post-MVP** — multi-device linking, voice/video, richer moderation tooling.

Each milestone is independently demoable and does not require revisiting earlier ones — that ordering is deliberate, so "no drastic changes at build time" holds in practice, not just on paper.
