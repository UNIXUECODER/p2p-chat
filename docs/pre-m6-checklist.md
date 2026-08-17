# Pre-M6 Checklist

This is the small-but-essential cleanup pass to do once before starting M6
(`node-daemon` composition root + local JSON-RPC/WebSocket API).

The goal is not to add new product scope. The goal is to keep M6 from inheriting
demo-era hazards, stale docs, or unverified assumptions.

## 1. Fix Local Build Verification

- Fix `JAVA_HOME` so Gradle runs on an accessible supported JDK, ideally JDK 21.
  The currently observed bad value was:
  `C:\Users\Abhimanyu\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
- Do not use JDK 25 as the Gradle launcher with the current Gradle/Kotlin DSL setup.
  Gradle 8.10 failed with `IllegalArgumentException: 25`.
- Run the full test suite:
  ```bash
  ./gradlew test
  ```
- Run the standalone demo checks that do not require multiple terminals:
  ```bash
  ./gradlew :node-daemon:runHlcDemo
  ./gradlew :node-daemon:runChatWireCodecDemo
  ./gradlew :node-daemon:runWireCodecDemo
  ./gradlew :node-daemon:runFileTransferDemo
  ./gradlew :node-daemon:runChunkResumeDemo
  ./gradlew :node-daemon:runStorageDemo
  ```

## 2. Confirm M5d On Real Hardware

- Mark the normal M5d happy path as verified. The latest run showed:
  - sender establishes PQXDH;
  - listener receives, persists, marks read, sends delivery/read receipts;
  - listener replies;
  - sender receives and persists reply;
  - sender marks reply read and sends delivery/read receipts;
  - listener receives receipts and marks its own reply state.
- Add or run a targeted duplicate-delivery test before M6:
  - send the same `ChatMessagePayload.messageId` twice;
  - confirm it is re-acked;
  - confirm it is not persisted twice;
  - confirm the listener does not auto-reply twice.
- Confirm the same test with `-Pmarkread=false` on both sides, so delivery-only flow is proven too.
- Query both SQLite DBs after a run and confirm message states are exactly what the logs imply.

## 3. Remove Callback Send Deadlock Risk From File Transfer

- `ChatListenerMain` and `ChatSenderMain` already avoid calling `sendEnvelope` synchronously
  from an `OnEnvelopeMessage` callback.
- Apply the same rule to file transfer before M6 copies patterns from the demo mains.
- Fix these call paths:
  - `FileReceiverMain.sendMessage(...)`, called from the receive callback after an offer.
  - `FileSenderMain.sendMessage(...)`, called from the receive callback after a chunk request.
- Use the same basic shape as chat:
  - compose outgoing encrypted frames first;
  - send them from a worker / `CompletableFuture.runAsync`;
  - catch and log exceptions inside the async task.
- After fixing, run a real M4c/M4d file-transfer test again.

## 4. Tighten Frame Decoder Validation

- `EncryptedFrameCodec.decode` should reject unknown marker bytes.
  Today any marker other than `0x01` effectively becomes Whisper.
- `RelayFrameCodec.decode` should reject unknown marker bytes.
  Today any marker other than the forward marker effectively becomes delivery.
- Consider adding minimal length checks before reading length-prefixed fields in:
  - `RelayFrameCodec`
  - `DiscoveryFrameCodec`
  - `FileTransferMessageCodec`
  - `ChatMessageCodec`
- Add tests for malformed/truncated inputs where cheap.
  This does not need to become a full fuzzing project before M6.

## 5. Update Stale README / Spec State

- Update the README milestone table:
  - M5 should say M5a-M5d implemented.
  - M5d should mention happy-path real-hardware verification if accepted.
  - M6 remains next.
- Update `docs/architecture-spec.md` milestone log:
  - M5d still appears as upcoming in at least one section.
  - Bring it into line with the README.
- Fix stale comments that still say `OnEnvelopeMessage` exposes a jvm-libp2p `PeerId`.
  The public API now uses `com.p2pchat.model.PeerId`.
- Keep the distinction clear:
  - app identity hex id;
  - libp2p peer id;
  - Signal session address name.

## 6. Decide M6's Canonical Runtime Peer Identity

- Before building the daemon state model, decide which peer id is canonical for runtime routing,
  conversation ids, message sender ids, discovery records, and UI/API responses.
- The practical choice today is probably the libp2p peer id, because:
  - network callbacks provide it;
  - multiaddrs contain it;
  - relay/discovery are keyed by it;
  - direct conversation ids already use it in chat.
- If the app identity hex id remains visible, document it as local identity metadata, not the
  routing identity.
- Avoid letting M6 introduce a third accidental identity convention.

## 7. Add HLC Remote Timestamp Guardrails

- `HybridLogicalClock.update` currently accepts any remote timestamp.
- Before untrusted peers can feed the long-running daemon indefinitely, add a policy for remote
  timestamps far in the future.
- A minimal M6-ready policy is enough:
  - define a maximum tolerated future drift;
  - reject or quarantine messages beyond that drift;
  - log the event clearly.
- Keep the current pure algorithm intact; put the trust boundary in the caller or a wrapper.

## 8. Plan Shared Decrypted Message Dispatch

- M4 file-transfer and M5 chat currently have separate sealed hierarchies/codecs.
- That was correct for isolated demos.
- M6 is the first place a single live daemon session may need to handle both chat and file
  transfer messages.
- Before implementing the API server, decide the dispatch boundary:
  - either a small shared application-message envelope;
  - or a central decoder that switches on the existing marker values and routes to chat/file.
- Do not let every handler independently guess which codec to try first.

## 9. Define The M6 Send Path Before Coding It Everywhere

- Today chat/file demos call `sendEnvelope` directly.
- M6 should have one outbound send abstraction that handles:
  - direct-first attempt;
  - relay fallback via `ConnectionStrategy`;
  - timeout;
  - async execution off libp2p callback threads;
  - result reporting (`DIRECT`, `RELAYED`, `UNREACHABLE`);
  - logging/error propagation to the API layer.
- Do this once early in M6 rather than scattering direct calls across JSON-RPC methods.

## 10. Decide Discovery Record Contents For M6

- Current discovery records are opaque bytes and have so far carried addresses only.
- M6 needs a concrete record shape before replacing manual address/bundle hand-carrying.
- Decide whether the M6 discovery record includes:
  - dialable addresses;
  - current pre-key bundle;
  - relay preference;
  - timestamp / expiry;
  - record version.
- If pre-key bundles enter discovery, record signatures become security-critical.
  Do not publish unsigned bundle-bearing records.

## 11. Clarify Pre-Key Bundle Lifecycle

- Current listener demos publish a fresh bundle file on each restart.
- M6 needs a daemon policy:
  - when bundles are generated;
  - how many one-time prekeys are available;
  - whether bundles are rotated;
  - whether used prekeys are consumed/persisted;
  - what happens after daemon restart.
- It is acceptable for M6 to keep this simple, but it should be explicit.

## 12. Quarantine Demo Mains From Daemon Architecture

- Treat `node-daemon` demo mains as proven mechanisms, not final architecture.
- M6 should avoid copying these demo habits directly:
  - static helper duplication;
  - direct `System.out` logging as state reporting;
  - direct `Thread.currentThread().join()`;
  - one-shot process assumptions;
  - manual bundle/address CLI passing;
  - per-main ad hoc send/reply logic.
- Keep them runnable as regression/proof tools, but build the real daemon around services.

## 13. Decide Storage Transaction Boundaries

- M6 should define transaction boundaries for receive processing.
- For chat receive, decide whether these should be atomic together:
  - save/upsert conversation;
  - dedup check;
  - save message;
  - local read-state update;
  - enqueue outgoing delivery/read receipt.
- For file receive, decide whether these should be atomic or explicitly ordered:
  - write chunk bytes to disk;
  - mark chunk received;
  - update transfer state.
- Do not rely on the demo order being automatically good enough for a long-running daemon.

## 14. Add Minimal Daemon-Facing Error Types

- M6 API calls should not expose raw exceptions as user-facing results.
- Define a small internal error/result vocabulary for:
  - peer unreachable;
  - relay unavailable;
  - malformed peer record;
  - crypto/session failure;
  - duplicate message;
  - storage failure;
  - invalid API request.
- Keep it small. The point is consistent API behavior, not a huge error framework.

## 15. Keep Known Security Gaps Explicitly Out Of Product Claims

These do not block local M6 development, but they do block real-user/distribution claims:

- plaintext `identity.key`;
- plaintext Signal identity/session-related material;
- plaintext `messages.plaintext_cache`;
- unsigned discovery records;
- no robust peer verification / safety-number flow;
- libsignal-client AGPL distribution implications;
- no abuse/blocking UX;
- no retry/resend reliability yet.

## 16. Stop Treating Logs As The Only Verification Surface

- Keep console logs, but before M6 prefer verification through:
  - tests;
  - SQLite state queries;
  - return values / API events;
  - deterministic demo assertions.
- For M5d specifically, add a small storage-state check after a chat run if practical.

## 17. Optional But Worth Doing Before The Big M6 Pass

- Add an SLF4J binding or intentionally suppress the no-provider warning.
- Add a short `docs/current-state.md` or update README with a crisp "where we are now."
- Stop Gradle daemons after JDK changes if weird build behavior persists:
  ```bash
  ./gradlew --stop
  ```
- Consider adding `--no-daemon` to important verification commands while the Java setup is being fixed.

## Go / No-Go For M6

Proceed to M6 when:

- `./gradlew test` runs cleanly on a supported JDK;
- M5d happy path is accepted as verified;
- duplicate dedup has been tested;
- file-transfer callback sends no longer use synchronous `sendEnvelope`;
- frame decoders reject unknown markers;
- README/spec no longer contradict the current state;
- M6 has one intended outbound send path and one intended decrypted-message dispatch boundary.

At that point, the remaining items are M6 design work, not pre-M6 cleanup.
