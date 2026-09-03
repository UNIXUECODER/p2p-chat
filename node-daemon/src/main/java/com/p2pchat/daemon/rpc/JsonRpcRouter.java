package com.p2pchat.daemon.rpc;

import com.p2pchat.daemon.contact.ContactAddResult;
import com.p2pchat.daemon.contact.ContactService;
import com.p2pchat.daemon.json.JsonArray;
import com.p2pchat.daemon.json.JsonCodec;
import com.p2pchat.daemon.json.JsonObject;
import com.p2pchat.daemon.json.JsonValue;
import com.p2pchat.daemon.routing.PeerRoutingTable;
import com.p2pchat.daemon.session.ChatSendResult;
import com.p2pchat.daemon.session.DaemonEventListener;
import com.p2pchat.daemon.session.FileSendResult;
import com.p2pchat.daemon.session.SessionManager;
import com.p2pchat.daemon.ws.WebSocketSession;
import com.p2pchat.daemon.ws.WebSocketTextHandler;
import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.IdentityNotFoundException;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.DeliveryState;
import com.p2pchat.storage.model.Message;
import com.p2pchat.storage.model.Pagination;
import com.p2pchat.storage.model.PeerRoute;
import com.p2pchat.storage.model.TransferState;

import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.state.PreKeyBundle;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * M6g-4: the JSON-RPC 2.0 router {@code docs/architecture-spec.md §7} describes and {@code
 * docs/M6g-gap-analysis-and-plan.md §3} scopes — the piece M6g-1 through M6g-3 existed to make
 * buildable. Implements {@link WebSocketTextHandler} (parses incoming requests, dispatches to the
 * right backend call, sends responses back) and {@link DaemonEventListener} (translates {@code
 * SessionManager}'s callbacks into {@code event.*} push notifications), per that plan's own §2.5
 * design decision — one router, both directions, not two separate objects that would need to
 * coordinate which {@code WebSocketSession}s exist.
 *
 * <p><b>Construction-order assumption, stated explicitly.</b> This router is built to be wired
 * into an already-started daemon (M6h's future {@code DaemonMain} composition root) — every
 * constructor dependency below is expected to already be fully initialized, in particular {@code
 * sessionManager}, which must already have had {@link SessionManager#start} called (this class
 * calls {@link SessionManager#localPeerId()} freely, which throws if that hasn't happened). This
 * is not this router's own bootstrap sequencing to own — first-run identity/network bring-up is
 * M6h's job. One real consequence of this assumption, named rather than silently hit later: by
 * the time this router can answer any call, an identity necessarily already exists (starting
 * {@code SessionManager} requires one), so {@code identity.create}'s success path — while
 * genuinely correct and unit-testable in isolation — is not reachable through a normally-composed
 * running daemon. See {@link #handleIdentityCreate}'s own Javadoc.
 *
 * <p><b>{@link #attachEventBroadcaster} exists for the identical reason {@code
 * PrintingDaemonEventListener.attachSessionManager} does</b> (see that class's own Javadoc): this
 * router must be constructed before {@code DaemonWebSocketServer} (which needs it as a {@link
 * WebSocketTextHandler}), but this router needs a way to push events out to every connected
 * session, so that capability is threaded through after both exist, not at construction — a real
 * ordering constraint, not a design preference. Depends on {@link EventBroadcaster} — a single-
 * method abstraction over {@code DaemonWebSocketServer.broadcast(String)} — rather than that
 * class directly, discovered to be necessary while writing this router's own tests: {@code
 * DaemonWebSocketServer} is {@code final} and directly owns a real Netty event-loop/channel
 * internally (confirmed by reading its real implementation), so nothing could stand in for it in
 * a unit test without this seam. {@code server::broadcast} already satisfies {@link
 * EventBroadcaster} exactly, so real wiring (M6h's future {@code DaemonMain}) costs nothing beyond
 * a method reference; a test supplies a capturing lambda instead. Matches this project's
 * established pattern for exactly this situation — {@code ContactService.DiscoveryLookup} exists
 * for the identical reason, decoupling from a concrete network-touching dependency its own tests
 * can't construct for real.
 *
 * <p><b>{@code contentType} is currently rejected unless {@code "text/plain"}</b> — {@code
 * SessionManager.sendChatMessage} hardcodes {@code "text/plain"} for every outgoing {@code
 * ChatMessagePayload} internally (confirmed by reading the real implementation), so honoring a
 * caller's stated {@code contentType} without it actually taking effect would silently
 * misrepresent what was sent. Named as a real, tracked backend gap in the README's M6g-4 section,
 * not solved here — redesigning {@code ChatMessagePayload}'s wire shape is out of this router's
 * own scope.
 *
 * <p><b>No live end-to-end test in this milestone.</b> Matching {@code docs/M6g-gap-analysis-and-
 * plan.md §3}'s own "Deferred to M6h: live end-to-end test with a real WebSocket client" —
 * verification here is unit tests against a real, fully-started {@code SessionManager} (real
 * SQLite, real {@code SqliteSignalProtocolStore}, a fake {@code PeerNetworkService} — see {@code
 * JsonRpcRouterTest}'s own setup), calling the package-private {@link #handle} directly rather
 * than {@link #onMessage} (see that method's own Javadoc for why {@code WebSocketSession}
 * couldn't be constructed in a test at all), and a capturing {@link EventBroadcaster} lambda in
 * place of a real {@code DaemonWebSocketServer}. The same testable-seam discipline this project
 * has applied at every milestone boundary since M6b. A real {@code DaemonWebSocketServer} + real
 * WebSocket client + this router, wired together and run for real, is M6h's own checkpoint to
 * prove — the same "prove the seam in isolation, then prove the live wiring separately" pattern
 * the M6g-3 real-hardware checkpoint (see README) already established works for this project.
 */
public final class JsonRpcRouter implements WebSocketTextHandler, DaemonEventListener {

    private final IdentityService identityService;
    private final Path dataDir;
    private final SessionManager sessionManager;
    private final PeerNetworkService network;
    private final StorageService storage;
    private final PeerRoutingTable routingTable;
    private final ContactService contactService;

    /**
     * The single capability this router needs from whatever transport is actually pushing bytes
     * to connected clients — see class Javadoc for why this exists instead of a direct {@code
     * DaemonWebSocketServer} dependency.
     */
    @FunctionalInterface
    public interface EventBroadcaster {
        void broadcast(String text);
    }

    private volatile EventBroadcaster eventBroadcaster;

    public JsonRpcRouter(IdentityService identityService, Path dataDir, SessionManager sessionManager,
                          PeerNetworkService network, StorageService storage, PeerRoutingTable routingTable,
                          ContactService contactService) {
        this.identityService = identityService;
        this.dataDir = dataDir;
        this.sessionManager = sessionManager;
        this.network = network;
        this.storage = storage;
        this.routingTable = routingTable;
        this.contactService = contactService;
    }

    /** See class Javadoc — must be called once a real broadcaster exists, before any push event needs to go out. */
    public void attachEventBroadcaster(EventBroadcaster eventBroadcaster) {
        this.eventBroadcaster = eventBroadcaster;
    }

    // ==================================================================== WebSocketTextHandler

    @Override
    public void onMessage(WebSocketSession session, String text) {
        handle(text).thenAccept(responseText -> {
            if (responseText != null) {
                session.send(responseText);
            }
        });
    }

    /**
     * The actual request-handling logic behind {@link #onMessage}, factored out so it's testable
     * without a real {@link WebSocketSession} — that class is {@code final}, with a package-
     * private constructor directly wrapping a real Netty {@code Channel} (confirmed by reading it
     * directly, the same way {@code DaemonWebSocketServer}'s own constraint was confirmed before
     * introducing {@link EventBroadcaster} for the identical reason) — nothing could stand in for
     * one in a unit test. Package-private, not public — an internal seam for {@code
     * JsonRpcRouterTest} (same package), not part of this class's real public API.
     *
     * @return the response text to send back, or {@code null} if nothing should be sent — either
     *         a single notification, or a batch where every element was one.
     */
    CompletableFuture<String> handle(String text) {
        JsonValue parsed;
        try {
            parsed = JsonCodec.parse(text);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new JsonRpcResponse.Error(null, JsonRpcError.parseError()).toJsonText());
        }

        if (parsed instanceof JsonArray batch) {
            return handleBatch(batch);
        }
        return dispatch(parsed).thenApply(response -> response == null ? null : response.toJsonText());
    }

    private CompletableFuture<String> handleBatch(JsonArray batch) {
        // JSON-RPC 2.0 §6: "If the batch rpc call itself fails to be recognized... the Response
        // from the Server MUST be a single Response object" -- an empty array is exactly that
        // case (the spec's own example response is Invalid Request), not an empty batch result.
        if (batch.elements().isEmpty()) {
            return CompletableFuture.completedFuture(new JsonRpcResponse.Error(null,
                    JsonRpcError.invalidRequest("batch array must not be empty")).toJsonText());
        }

        List<CompletableFuture<JsonRpcResponse>> futures = batch.elements().stream().map(this::dispatch).toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            JsonArray.Builder responses = JsonArray.builder();
            for (CompletableFuture<JsonRpcResponse> future : futures) {
                JsonRpcResponse response = future.join(); // safe: allOf above already guarantees completion
                if (response != null) {
                    responses.add(response.toJsonValue());
                }
            }
            JsonArray built = responses.build();
            // §6: "the Server should not return an empty Array... it should return nothing at
            // all" -- exactly the all-notifications case.
            return built.elements().isEmpty() ? null : JsonCodec.write(built);
        });
    }

    /**
     * Parses and dispatches one JSON-RPC request. Always resolves (never completes exceptionally,
     * matching this project's established convention), but MAY resolve to a genuine Java {@code
     * null} — exactly when the request validated as a notification (no {@code id} member) and its
     * method executed without needing to report a parse-level failure first. A parse/validation
     * failure ALWAYS resolves to a real {@link JsonRpcResponse.Error} (with {@code id: null} if
     * the id itself couldn't be determined) — a malformed request is never silently swallowed
     * just because it also happens to lack an id; only a request that's valid all the way through
     * dispatch gets to suppress its own response.
     */
    private CompletableFuture<JsonRpcResponse> dispatch(JsonValue raw) {
        JsonRpcRequest request;
        try {
            request = JsonRpcRequest.parse(raw);
        } catch (JsonRpcRequestException e) {
            return CompletableFuture.completedFuture(
                    new JsonRpcResponse.Error(e.bestEffortId(), JsonRpcError.invalidRequest(e.getMessage())));
        }
        CompletableFuture<JsonRpcResponse> result = dispatchMethod(request);
        return request.isNotification() ? result.thenApply(ignored -> null) : result;
    }

    private CompletableFuture<JsonRpcResponse> dispatchMethod(JsonRpcRequest request) {
        try {
            return switch (request.method()) {
                case "identity.create" -> handleIdentityCreate(request);
                case "identity.get" -> handleIdentityGet(request);
                case "contacts.add" -> handleContactsAdd(request);
                case "contacts.list" -> handleContactsList(request);
                case "conversations.list" -> handleConversationsList(request);
                case "conversations.createGroup" -> deferredMethod(request,
                        "group conversations are available in a future version");
                case "messages.send" -> handleMessagesSend(request);
                case "messages.history" -> handleMessagesHistory(request);
                case "files.send" -> handleFilesSend(request);
                case "files.accept" -> handleFilesAccept(request);
                case "files.cancel" -> deferredMethod(request,
                        "canceling file transfers is available in a future version");
                case "network.status" -> handleNetworkStatus(request);
                case "network.connectedPeers" -> handleNetworkConnectedPeers(request);
                default -> CompletableFuture.completedFuture(
                        new JsonRpcResponse.Error(request.id(), JsonRpcError.methodNotFound(request.method())));
            };
        } catch (JsonRpcParamException e) {
            return CompletableFuture.completedFuture(
                    new JsonRpcResponse.Error(request.id(), JsonRpcError.invalidParams(e.getMessage())));
        } catch (Exception e) {
            // A handler threw something genuinely unexpected -- extends SessionManager's own "a
            // daemon does not crash on one peer's malformed input" principle one layer up: this
            // daemon does not crash on one client's malformed RPC call either.
            return CompletableFuture.completedFuture(
                    new JsonRpcResponse.Error(request.id(), JsonRpcError.internalError(String.valueOf(e.getMessage()))));
        }
    }

    private static CompletableFuture<JsonRpcResponse> deferredMethod(JsonRpcRequest request, String detail) {
        return CompletableFuture.completedFuture(new JsonRpcResponse.Error(request.id(),
                JsonRpcError.of(DaemonErrorCode.METHOD_NOT_FOUND, request.method() + ": " + detail)));
    }

    // ==================================================================== method handlers

    /**
     * <b>Success path is real but practically unreachable in a normally-composed daemon</b> — see
     * class Javadoc's construction-order note. Guards against {@code
     * IdentityService.createIdentity}'s own real behavior: it unconditionally overwrites {@code
     * identity.pub}/{@code .key}/{@code .meta} with no existence check of its own (confirmed by
     * reading {@code JavaIdentityService} directly, not assumed) — calling it on a daemon that
     * already has an identity would silently destroy the keys the already-running {@code
     * SessionManager} depends on, while that running network layer kept operating under the now-
     * stale in-memory peer id. Refused outright instead, mapped to {@link
     * DaemonErrorCode#INVALID_REQUEST} for lack of a more specific named value — see that enum's
     * own Javadoc for why this is the closest fit among the ten it's limited to.
     */
    private CompletableFuture<JsonRpcResponse> handleIdentityCreate(JsonRpcRequest request) throws JsonRpcParamException {
        String displayName = request.optionalString("displayName", "anonymous");
        if (identityService.hasIdentity()) {
            return CompletableFuture.completedFuture(new JsonRpcResponse.Error(request.id(),
                    JsonRpcError.of(DaemonErrorCode.INVALID_REQUEST,
                            "an identity already exists for this daemon; identity.create refuses to overwrite it")));
        }
        Identity identity = identityService.createIdentity(displayName);
        SignalIdentityVault.loadOrCreate(dataDir);
        return CompletableFuture.completedFuture(new JsonRpcResponse.Success(
                request.id(), RpcJsonMapper.identityJson(identity, sessionManager.localPeerId())));
    }

    private CompletableFuture<JsonRpcResponse> handleIdentityGet(JsonRpcRequest request) {
        Identity identity;
        try {
            identity = identityService.loadIdentity();
        } catch (IdentityNotFoundException e) {
            // See class Javadoc -- should not be reachable given this router's own construction-
            // order assumption, but handled explicitly rather than left to throw out of this
            // method, for the same reason every other handler here is defensive about backend
            // state it doesn't fully control.
            return CompletableFuture.completedFuture(new JsonRpcResponse.Error(request.id(),
                    JsonRpcError.of(DaemonErrorCode.INVALID_REQUEST, "no identity exists for this daemon yet")));
        }
        return CompletableFuture.completedFuture(new JsonRpcResponse.Success(
                request.id(), RpcJsonMapper.identityJson(identity, sessionManager.localPeerId())));
    }

    private CompletableFuture<JsonRpcResponse> handleContactsAdd(JsonRpcRequest request) throws JsonRpcParamException {
        String inviteCode = request.requireString("inviteCode");
        return contactService.addContact(inviteCode).thenApply(result -> switch (result) {
            case ContactAddResult.Added added ->
                    new JsonRpcResponse.Success(request.id(), RpcJsonMapper.contactJson(added.contact()));
            case ContactAddResult.Failed failed ->
                    new JsonRpcResponse.Error(request.id(), JsonRpcError.of(mapContactFailureReason(failed.reason()), failed.message()));
        });
    }

    // Mapping given directly by ContactAddResult.Reason's own Javadoc, except INTERNAL_ERROR,
    // which that Javadoc explicitly leaves for "whoever composes the eventual DaemonErrorCode
    // mapping" to decide -- STORAGE_FAILURE is the closest fit: INTERNAL_ERROR fires only after a
    // successful, verified discovery lookup, at which point the realistic remaining failure is a
    // StorageService write (see ContactService.describeFailure's own comment on that call site).
    private static DaemonErrorCode mapContactFailureReason(ContactAddResult.Reason reason) {
        return switch (reason) {
            case MALFORMED_INVITE_CODE, VERIFICATION_FAILED -> DaemonErrorCode.MALFORMED_RECORD;
            case PEER_NOT_FOUND -> DaemonErrorCode.PEER_UNREACHABLE;
            case NO_DISCOVERY_SERVER, LOOKUP_FAILED -> DaemonErrorCode.RELAY_UNAVAILABLE;
            case INTERNAL_ERROR -> DaemonErrorCode.STORAGE_FAILURE;
        };
    }

    private CompletableFuture<JsonRpcResponse> handleContactsList(JsonRpcRequest request) {
        return CompletableFuture.completedFuture(
                new JsonRpcResponse.Success(request.id(), RpcJsonMapper.contactsJson(storage.listContacts())));
    }

    private CompletableFuture<JsonRpcResponse> handleConversationsList(JsonRpcRequest request) {
        return CompletableFuture.completedFuture(
                new JsonRpcResponse.Success(request.id(), RpcJsonMapper.conversationsJson(storage.listConversations())));
    }

    private CompletableFuture<JsonRpcResponse> handleMessagesSend(JsonRpcRequest request) throws JsonRpcParamException {
        String conversationId = request.requireString("conversationId");
        String contentType = request.optionalString("contentType", "text/plain");
        String content = request.requireString("content");

        if (!contentType.equals("text/plain")) {
            // See class Javadoc's "contentType is currently rejected" note.
            throw new JsonRpcParamException(
                    "\"contentType\" must currently be \"text/plain\" -- SessionManager does not yet support other content types");
        }

        PeerId targetPeerId = resolveOtherPartyOfDirectConversation(conversationId);
        PeerRoute route = routingTable.get(targetPeerId);
        String directMultiaddr = route == null ? null : route.directMultiaddr();
        String relayMultiaddr = route == null ? null : route.relayMultiaddr();
        PreKeyBundle bundle = decodeBundleIfPresent(route);

        return sessionManager.sendChatMessage(targetPeerId, directMultiaddr, relayMultiaddr, bundle, conversationId, content)
                .thenApply(result -> {
                    // messages.send's documented response is exactly { messageId } (architecture-
                    // spec.md §7) -- no status field. sendChatMessage's own "never completes
                    // exceptionally" contract means this always resolves to a real ChatSendResult,
                    // so success is reported here even when result.status() is UNREACHABLE -- the
                    // send genuinely happened (or was genuinely attempted) from this daemon's
                    // perspective; the client can already learn about eventual delivery failure
                    // via messages.history's own state field. No push notification exists yet for
                    // a locally-detected send failure specifically -- a real, named gap, distinct
                    // from the already-tracked file-transfer-receipt gap, tracked in the README's
                    // M6g-4 section rather than solved here (it is SessionManager's own event
                    // emission that would need to grow a new call site, not this router's job to
                    // invent one on SessionManager's behalf).
                    return (JsonRpcResponse) new JsonRpcResponse.Success(request.id(),
                            JsonObject.builder().put("messageId", result.messageId()).build());
                });
    }

    private CompletableFuture<JsonRpcResponse> handleMessagesHistory(JsonRpcRequest request) throws JsonRpcParamException {
        String conversationId = request.requireString("conversationId");
        String cursor = request.optionalString("cursor", null);
        int limit = request.optionalInt("limit", 50);
        if (limit <= 0) {
            throw new JsonRpcParamException("\"limit\" must be positive");
        }
        List<Message> messages = storage.queryMessages(conversationId, new Pagination(cursor, limit));
        return CompletableFuture.completedFuture(
                new JsonRpcResponse.Success(request.id(), RpcJsonMapper.messagesJson(messages)));
    }

    private CompletableFuture<JsonRpcResponse> handleFilesSend(JsonRpcRequest request) throws JsonRpcParamException {
        String conversationId = request.requireString("conversationId");
        String filePath = request.requireString("filePath");

        PeerId targetPeerId = resolveOtherPartyOfDirectConversation(conversationId);
        PeerRoute route = routingTable.get(targetPeerId);
        String directMultiaddr = route == null ? null : route.directMultiaddr();
        String relayMultiaddr = route == null ? null : route.relayMultiaddr();

        return sessionManager.sendFile(targetPeerId, directMultiaddr, relayMultiaddr, Path.of(filePath))
                .thenApply(result -> {
                    if (result.transferId() == null) {
                        // FileSendResult's own Javadoc: null transferId means no transfer was ever
                        // registered -- the file didn't exist, or offer construction failed before
                        // FileTransferHandler ever knew about it. Genuinely different from a
                        // registered-but-UNREACHABLE outcome (messages.send's own equivalent case
                        // is still reported as success -- see that handler's own comment) because
                        // there is no transferId at all here to report, not merely a failed one.
                        return (JsonRpcResponse) new JsonRpcResponse.Error(request.id(),
                                JsonRpcError.of(DaemonErrorCode.PEER_UNREACHABLE,
                                        "file offer could not be sent (file not found, or the offer failed before registration)"));
                    }
                    return (JsonRpcResponse) new JsonRpcResponse.Success(request.id(),
                            JsonObject.builder().put("transferId", result.transferId()).build());
                });
    }

    private CompletableFuture<JsonRpcResponse> handleFilesAccept(JsonRpcRequest request) throws JsonRpcParamException {
        String transferId = request.requireString("transferId");
        String savePath = request.requireString("savePath");
        // Pure delegation, matching SessionManager.acceptFileTransfer's own shape -- no
        // pre-validation of transferId here (e.g. "does this offer exist/is it still pending")
        // beyond what DefaultFileTransferHandler already does internally (its own already-tested
        // "duplicate accept protection" and quiet-ignore-on-unknown-id behavior -- see README's
        // M6g-3 section), trusted rather than re-implemented here.
        sessionManager.acceptFileTransfer(transferId, Path.of(savePath));
        return CompletableFuture.completedFuture(new JsonRpcResponse.Success(request.id(), RpcJsonMapper.emptyResult()));
    }

    private CompletableFuture<JsonRpcResponse> handleNetworkStatus(JsonRpcRequest request) {
        return CompletableFuture.completedFuture(new JsonRpcResponse.Success(request.id(), buildNetworkStatusJson()));
    }

    private CompletableFuture<JsonRpcResponse> handleNetworkConnectedPeers(JsonRpcRequest request) {
        JsonArray.Builder peers = JsonArray.builder();
        for (PeerRoute route : routingTable.list()) {
            if (!sessionManager.hasSession(route.peerId())) {
                continue;
            }
            peers.add(RpcJsonMapper.connectedPeerJson(route.peerId(), resolveDisplayName(route), route.lastSeen(), true));
        }
        return CompletableFuture.completedFuture(new JsonRpcResponse.Success(request.id(), peers.build()));
    }

    // ==================================================================== shared helpers

    /**
     * Recovers the OTHER party of a {@code direct-<peerA>-<peerB>} conversation id, given this
     * daemon's own peer id — the inverse of {@code SessionManager}'s own private {@code
     * deriveDirectConversationId}. Deliberately duplicated logic, not shared with it or with
     * {@code DefaultFileTransferHandler}'s own separately-duplicated copy — matching this
     * project's established precedent (see {@code SessionManager}'s own comment on why that
     * method stayed private rather than being extracted for a second caller) — though the
     * original reason there (keeping a libsignal-free class's compilation decoupled from
     * libsignal) does not strictly apply to this router, which already depends on {@code
     * SessionManager} directly. Kept duplicated anyway, for two reasons: consistency with an
     * established, twice-repeated convention this project has not revisited, and because this is
     * genuinely a different operation (recovering an unknown party from a known one) than {@code
     * deriveDirectConversationId}'s (constructing an id from two already-known parties), not the
     * same logic merely relocated.
     */
    private PeerId resolveOtherPartyOfDirectConversation(String conversationId) throws JsonRpcParamException {
        if (!conversationId.startsWith("direct-")) {
            throw new JsonRpcParamException(
                    "\"conversationId\" is not a direct conversation id (must start with \"direct-\")");
        }
        String remainder = conversationId.substring("direct-".length());
        int splitIndex = remainder.indexOf('-');
        if (splitIndex < 0) {
            throw new JsonRpcParamException("\"conversationId\" is malformed -- expected \"direct-<peerA>-<peerB>\"");
        }
        // Base58 (the alphabet every real peer id in this project uses -- confirmed against
        // Ed25519RecordKeys and every SignalProtocolAddress construction site) never contains
        // '-', so the first hyphen after "direct-" is always the one genuine separator -- not an
        // assumption made without checking.
        String peerA = remainder.substring(0, splitIndex);
        String peerB = remainder.substring(splitIndex + 1);
        if (peerA.isBlank() || peerB.isBlank()) {
            throw new JsonRpcParamException("\"conversationId\" is malformed -- expected \"direct-<peerA>-<peerB>\"");
        }
        String ownPeerId = sessionManager.localPeerId().value();
        if (peerA.equals(ownPeerId)) {
            return PeerId.of(peerB);
        }
        if (peerB.equals(ownPeerId)) {
            return PeerId.of(peerA);
        }
        throw new JsonRpcParamException("\"conversationId\" does not include this daemon's own peer id");
    }

    private PreKeyBundle decodeBundleIfPresent(PeerRoute route) {
        if (route == null || !route.hasPreKeyBundle()) {
            return null;
        }
        try {
            return PreKeyBundleCodec.decode(route.preKeyBundle());
        } catch (InvalidKeyException e) {
            // A corrupted/unparseable stored bundle must not crash a send -- sendChatMessage/
            // sendFile already treat a null bundle as "no session to establish speculatively,"
            // exactly the behavior a genuinely-absent bundle produces, so falling back to that is
            // strictly safer than failing a request over a bundle that was never going to be used
            // unless a session didn't already exist anyway.
            System.err.println("[json-rpc-router] stored pre-key bundle for " + route.peerId() + " failed to decode: " + e);
            return null;
        }
    }

    private String resolveDisplayName(PeerRoute route) {
        Contact contact = storage.getContact(route.peerId());
        if (contact != null && contact.displayName() != null) {
            return contact.displayName();
        }
        return route.displayName();
    }

    private JsonObject buildNetworkStatusJson() {
        PeerId localPeerId = sessionManager.localPeerId();
        String displayName;
        try {
            displayName = identityService.loadIdentity().displayName();
        } catch (IdentityNotFoundException e) {
            // See class Javadoc's construction-order note -- should not be reachable, but this is
            // an event-emission/read path with no caller to report an RPC error to, so this
            // resolves to null rather than throwing out of onNetworkStatusChanged.
            displayName = null;
        }
        int connectedPeerCount = 0;
        for (PeerRoute route : routingTable.list()) {
            if (sessionManager.hasSession(route.peerId())) {
                connectedPeerCount++;
            }
        }
        // relayConnected is hardcoded false -- SessionManager.start() does not wire a
        // RelayEventHandler yet (see that class's own Javadoc), so there is no relay-connection
        // state to report truthfully until M6h. Matches docs/M6g-gap-analysis-and-plan.md §2.4
        // exactly: "false until M6h."
        return RpcJsonMapper.networkStatusJson(localPeerId, displayName, network.listenAddresses(), false, connectedPeerCount);
    }

    private void broadcastEvent(String eventName, JsonValue params) {
        EventBroadcaster broadcaster = this.eventBroadcaster;
        if (broadcaster == null) {
            return; // not yet attached -- e.g. a unit test exercising this router standalone
        }
        JsonObject envelope = JsonObject.builder()
                .put("jsonrpc", "2.0")
                .put("method", eventName)
                .put("params", params)
                .build();
        broadcaster.broadcast(JsonCodec.write(envelope));
    }

    // ==================================================================== DaemonEventListener
    //
    // Five callbacks; three map directly onto architecture-spec.md §7's own named push events
    // (event.message.received, event.transfer.progress, event.network.statusChanged). The other
    // two -- onDeliveryStateChanged, onFileOfferReceived -- have no §7 precedent at all: that
    // table predates M6g-3's finalized DaemonEventListener interface (this document's own header
    // says it was written "between M6f (done) and M6g (next)"), so it could not have named
    // callbacks that didn't exist yet. Forwarding all five, not just the three §7 happens to
    // name, is a deliberate completeness decision, not scope creep -- dropping either would leave
    // a real, already-built SessionManager capability with no way to reach a client at all: a UI
    // has no way to show delivery ticks without event.message.deliveryStateChanged, and no way to
    // even learn a file offer arrived (to decide whether to call files.accept) without
    // event.file.offerReceived.

    @Override
    public void onMessageReceived(Message message) {
        broadcastEvent("event.message.received", RpcJsonMapper.messageJson(message));
    }

    @Override
    public void onDeliveryStateChanged(String messageId, DeliveryState newState) {
        JsonObject params = JsonObject.builder()
                .put("messageId", messageId)
                .put("state", newState.name())
                .build();
        broadcastEvent("event.message.deliveryStateChanged", params);
    }

    @Override
    public void onFileOfferReceived(String transferId, PeerId sender, String fileName, long fileSize) {
        JsonObject params = JsonObject.builder()
                .put("transferId", transferId)
                .put("senderPeerId", sender.value())
                .put("fileName", fileName)
                .put("fileSize", fileSize)
                .build();
        broadcastEvent("event.file.offerReceived", params);
    }

    @Override
    public void onFileTransferProgress(String transferId, int chunksReceived, int totalChunks, TransferState state) {
        JsonObject params = JsonObject.builder()
                .put("transferId", transferId)
                .put("chunksReceived", chunksReceived)
                .put("totalChunks", totalChunks)
                .put("state", state.name())
                .build();
        broadcastEvent("event.transfer.progress", params);
    }

    @Override
    public void onNetworkStatusChanged() {
        broadcastEvent("event.network.statusChanged", buildNetworkStatusJson());
    }
}
