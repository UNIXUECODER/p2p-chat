package com.p2pchat.daemon.contact;

import com.p2pchat.daemon.routing.PeerRoutingTable;
import com.p2pchat.discovery.DiscoveryRecord;
import com.p2pchat.discovery.DiscoveryRecordCodec;
import com.p2pchat.discovery.DiscoveryRecordException;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.DiscoveryLookupResult;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.PeerRoute;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates {@code contacts.add} — {@code docs/M6g-gap-analysis-and-plan.md §2.1}'s flow:
 * decode invite code → discovery lookup → verify signed record → extract addresses + bundle →
 * persist contact → populate routing table. Deliberately stops there rather than also
 * establishing a Signal session, even though §2.1 offered that as an optional last step — see
 * below for why skipping it isn't just a simplification.
 *
 * <p><b>Why session establishment does NOT happen here.</b> Establishing a Signal session
 * consumes one of the peer's one-time pre-keys — a finite, not-yet-managed resource ({@code
 * PublishSignedRecordMain}'s own Javadoc names pre-key bundle refresh cadence as explicit,
 * still-unsolved M6h scope, after M6e-2 testing already hit a real bug from exactly this kind of
 * unmanaged consumption). {@code SessionManager.sendChatMessage} already has the right mechanism
 * for spending that resource exactly once, exactly when it's actually needed — its {@code
 * bundleIfNoSessionYet} parameter only establishes a session lazily, gated on {@code
 * signalStore.containsSession(remote)}, never speculatively. If {@code contacts.add} also
 * established a session eagerly, every contact added but never messaged would burn a one-time
 * prekey for nothing, compounding the exact problem M6h hasn't fixed yet rather than staying out
 * of its way. So this class persists the contact and the route — including the raw, undecoded
 * pre-key bundle bytes on {@link PeerRoute} (see that record's own Javadoc for why undecoded) —
 * and leaves the actual {@code PreKeyBundleCodec.decode} + session establishment to whatever
 * composes {@code messages.send} later (M6g-4), at the point a message is actually about to be
 * sent, reusing the exact lazy mechanism {@code sendChatMessage} already has rather than adding a
 * second path that does the same thing at the wrong time.
 *
 * <p><b>Why {@code verified} is always {@code false} on the {@link Contact} this creates.</b> A
 * verified signed discovery record proves the record wasn't forged or tampered with in transit —
 * a completely different trust dimension from {@code Contact.verified()}'s own meaning elsewhere
 * in this project (person-to-person safety-number-style confirmation, still-deferred pre-release
 * scope per the README). Setting {@code verified = true} here would conflate "this discovery
 * record's signature checks out" with "a human confirmed this really is who they claim to be" —
 * two different guarantees that happen to both involve the word "verify."
 *
 * <p><b>Why the discovery lookup is a functional interface, not a direct {@code
 * DiscoveryController} dependency.</b> Matches the pattern {@code SessionManager} already uses
 * for {@code FileTransferHandler}: a real {@code DiscoveryController} needs a live libp2p host
 * connected to a discovery server, which a unit test proving this class's own orchestration logic
 * (decode, verify, merge, persist) has no business standing up. A fake implementation returning a
 * pre-built, genuinely-signed record exercises the exact same code this class runs in production
 * — a real Ed25519 signature actually checked — just without a real network in the loop.
 */
public final class ContactService {

    public static final Duration DEFAULT_LOOKUP_TIMEOUT = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface DiscoveryLookup {
        CompletableFuture<DiscoveryLookupResult> lookup(String targetPeerId);
    }

    private final DiscoveryLookup discoveryLookup;
    private final StorageService storage;
    private final PeerRoutingTable routingTable;
    private final String defaultRelayMultiaddr;
    private final Duration lookupTimeout;

    public ContactService(DiscoveryLookup discoveryLookup, StorageService storage,
                           PeerRoutingTable routingTable, String defaultRelayMultiaddr) {
        this(discoveryLookup, storage, routingTable, defaultRelayMultiaddr, DEFAULT_LOOKUP_TIMEOUT);
    }

    /**
     * @param defaultRelayMultiaddr this daemon's own configured discovery/relay server, used
     *                              when an invite code omits its own ({@code d} absent); {@code
     *                              null} if this daemon has none configured. Where this value
     *                              ultimately comes from (a config file, a CLI flag) is a
     *                              daemon-startup concern, not this class's — see M6h.
     * @param lookupTimeout         how long to wait for {@code discoveryLookup} before treating
     *                              it as failed — separated from {@link #DEFAULT_LOOKUP_TIMEOUT}
     *                              the same way {@code ConnectionStrategy}'s own {@code
     *                              directTimeoutMillis} is constructor-injectable rather than
     *                              hardcoded, so a test can prove the timeout path in
     *                              milliseconds instead of actually waiting one out.
     */
    public ContactService(DiscoveryLookup discoveryLookup, StorageService storage, PeerRoutingTable routingTable,
                           String defaultRelayMultiaddr, Duration lookupTimeout) {
        this.discoveryLookup = discoveryLookup;
        this.storage = storage;
        this.routingTable = routingTable;
        this.defaultRelayMultiaddr = defaultRelayMultiaddr;
        this.lookupTimeout = lookupTimeout;
    }

    /**
     * Resolves an invite code into a real, verified contact.
     *
     * <p>Idempotent: if the invite code's peer ID is already a contact, returns it immediately
     * as {@link ContactAddResult.Added} with no network round trip. {@code StorageService
     * .saveContact} is a plain {@code INSERT} against a {@code peer_id} primary key (see that
     * interface's own Javadoc), so a caller re-scanning the same QR code, or double-tapping
     * "add," would otherwise hit a raw SQL constraint violation instead of the harmless no-op
     * this obviously should be.
     *
     * @return a future that always resolves to a {@link ContactAddResult}, matching {@code
     *         SessionManager.sendChatMessage}'s own established convention — never completes
     *         exceptionally, for the same reason it doesn't there: a malformed invite code, an
     *         unreachable peer, or a record that fails verification are ordinary, expected
     *         outcomes of this operation, not programming bugs.
     */
    public CompletableFuture<ContactAddResult> addContact(String inviteCode) {
        InviteCode decoded;
        try {
            decoded = InviteCodeCodec.decode(inviteCode);
        } catch (InviteCodeException e) {
            return CompletableFuture.completedFuture(
                    new ContactAddResult.Failed(ContactAddResult.Reason.MALFORMED_INVITE_CODE, e.getMessage()));
        }

        Contact existing = storage.getContact(decoded.peerId());
        if (existing != null) {
            return CompletableFuture.completedFuture(new ContactAddResult.Added(existing));
        }

        String discoveryAddress = decoded.discoveryAddress() != null ? decoded.discoveryAddress() : defaultRelayMultiaddr;
        if (discoveryAddress == null) {
            return CompletableFuture.completedFuture(new ContactAddResult.Failed(
                    ContactAddResult.Reason.NO_DISCOVERY_SERVER,
                    "Invite code has no discovery address, and this daemon has no default configured"));
        }

        return discoveryLookup.lookup(decoded.peerId().value())
                .orTimeout(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(lookupResult -> resolveLookupResult(decoded, lookupResult))
                .exceptionally(throwable -> describeFailure(throwable));
    }

    private ContactAddResult resolveLookupResult(InviteCode decoded, DiscoveryLookupResult lookupResult) {
        if (!lookupResult.found()) {
            return new ContactAddResult.Failed(ContactAddResult.Reason.PEER_NOT_FOUND,
                    "No discovery record found for " + decoded.peerId());
        }

        DiscoveryRecord record;
        try {
            record = DiscoveryRecordCodec.verifyAndDecode(lookupResult.payload(), decoded.peerId(), System.currentTimeMillis());
        } catch (DiscoveryRecordException e) {
            return new ContactAddResult.Failed(ContactAddResult.Reason.VERIFICATION_FAILED,
                    e.reason() + ": " + e.getMessage());
        }

        // The first published address, if any. ConnectionStrategy only ever tries one direct
        // candidate before falling back to relay (see its own Javadoc), so there's currently no
        // meaningful way to carry more than one forward — matches how PublishSignedRecordMain
        // already treats its own first listen address as "the" address, for the same reason.
        String directMultiaddr = record.addresses().isEmpty() ? null : record.addresses().get(0);

        // verified = false always — see this class's own Javadoc for why a verified discovery
        // record signature is not the same guarantee as this project's Contact.verified().
        Contact contact = new Contact(decoded.peerId(), decoded.displayName(), false, System.currentTimeMillis());
        storage.saveContact(contact);

        routingTable.upsert(new PeerRoute(
                decoded.peerId(),
                directMultiaddr,
                record.hasRelayMultiaddr() ? record.relayMultiaddr() : null,
                decoded.displayName(),
                record.hasPreKeyBundle() ? record.preKeyBundle() : null,
                System.currentTimeMillis()));

        return new ContactAddResult.Added(contact);
    }

    private ContactAddResult describeFailure(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
        if (cause instanceof TimeoutException) {
            return new ContactAddResult.Failed(ContactAddResult.Reason.LOOKUP_FAILED,
                    "Discovery lookup timed out after " + lookupTimeout);
        }
        if (cause instanceof RuntimeException) {
            // Anything the discovery lookup itself threw (network error, malformed transport
            // response) — genuinely a lookup failure, not this class's own bug.
            return new ContactAddResult.Failed(ContactAddResult.Reason.LOOKUP_FAILED,
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        }
        // Anything else reaching here failed inside resolveLookupResult AFTER a successful,
        // verified lookup (e.g. storage.saveContact throwing) — see ContactAddResult.Reason
        // .INTERNAL_ERROR's own Javadoc for why this is kept distinct from LOOKUP_FAILED rather
        // than folded into it.
        return new ContactAddResult.Failed(ContactAddResult.Reason.INTERNAL_ERROR,
                cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
    }
}
