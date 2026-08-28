package com.p2pchat.daemon.contact;

import com.p2pchat.daemon.routing.PeerRoutingTable;
import com.p2pchat.discovery.DiscoveryRecord;
import com.p2pchat.discovery.DiscoveryRecordCodec;
import com.p2pchat.discovery.Ed25519RecordKeys;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.DiscoveryLookupResult;
import com.p2pchat.storage.SqliteDatabase;
import com.p2pchat.storage.SqliteStorageService;
import com.p2pchat.storage.StorageService;
import com.p2pchat.storage.model.Contact;
import com.p2pchat.storage.model.PeerRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the {@code contacts.add} flow, against a REAL Ed25519-signed discovery
 * record — same key-generation pattern {@code DiscoveryRecordCodecTest} already established —
 * fed through a fake {@link ContactService.DiscoveryLookup} so no real network or relay server
 * is needed. This exercises the actual signature-verification path for real, not a mock standing
 * in for it; scenario {@link #aRecordSignedByTheWrongKeyIsRejectedNotSilentlyAccepted} in
 * particular is the one that matters most here.
 */
class ContactServiceTest {

    private SqliteDatabase database;
    private StorageService storage;
    private PeerRoutingTable routingTable;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        database = SqliteDatabase.openOrCreate(tempDir);
        storage = new SqliteStorageService(database);
        routingTable = new PeerRoutingTable(storage);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    private record Identity(byte[] rawPublicKey, byte[] rawPrivateKeySeed, String peerId) {
    }

    private static Identity generateIdentity() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] rawPublicKey = Ed25519RecordKeys.rawPublicKeyFromX509(kp.getPublic().getEncoded());
        byte[] pkcs8 = kp.getPrivate().getEncoded();
        byte[] rawSeed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
        String peerId = Ed25519RecordKeys.peerIdFromRawPublicKey(rawPublicKey);
        return new Identity(rawPublicKey, rawSeed, peerId);
    }

    @Test
    void happyPathVerifiesTheRecordPersistsTheContactAndPopulatesTheRoute() throws Exception {
        Identity bob = generateIdentity();
        byte[] fakeBundle = "fake-encoded-prekey-bundle-bytes".getBytes();
        DiscoveryRecord record = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + bob.peerId()),
                fakeBundle, "/ip4/198.51.100.1/tcp/4001/p2p/12D3KooWRelay",
                System.currentTimeMillis() + 60_000);
        byte[] signedWire = DiscoveryRecordCodec.encodeSigned(record, bob.rawPublicKey(), bob.rawPrivateKeySeed());

        ContactService.DiscoveryLookup lookup = targetPeerId -> {
            assertThat(targetPeerId).isEqualTo(bob.peerId());
            return CompletableFuture.completedFuture(new DiscoveryLookupResult(true, signedWire));
        };
        ContactService contactService = new ContactService(lookup, storage, routingTable, null);

        String inviteCode = InviteCodeCodec.encode(new InviteCode(
                new PeerId(bob.peerId()), "/ip4/9.9.9.9/tcp/4001/p2p/12D3KooWDiscovery", "Bob"));

        ContactAddResult result = contactService.addContact(inviteCode).get();

        assertThat(result).isInstanceOf(ContactAddResult.Added.class);
        Contact contact = ((ContactAddResult.Added) result).contact();
        assertThat(contact.peerId()).isEqualTo(new PeerId(bob.peerId()));
        assertThat(contact.displayName()).isEqualTo("Bob");
        // A verified discovery-record signature is not the same guarantee as person-to-person
        // verification — see ContactService's own Javadoc for why this must stay false here.
        assertThat(contact.verified()).isFalse();
        assertThat(storage.getContact(new PeerId(bob.peerId()))).isNotNull();

        PeerRoute route = routingTable.get(new PeerId(bob.peerId()));
        assertThat(route).isNotNull();
        assertThat(route.directMultiaddr()).isEqualTo("/ip4/203.0.113.5/tcp/9000/p2p/" + bob.peerId());
        assertThat(route.relayMultiaddr()).isEqualTo("/ip4/198.51.100.1/tcp/4001/p2p/12D3KooWRelay");
        assertThat(route.preKeyBundle()).isEqualTo(fakeBundle);
    }

    @Test
    void reAddingAnExistingContactIsIdempotentAndNeverTouchesTheNetwork() {
        PeerId alreadyKnown = new PeerId("12D3KooWAlreadyKnown1111111111111111111111111111");
        storage.saveContact(new Contact(alreadyKnown, "Existing Alice", true, 500L));

        AtomicBoolean lookupWasCalled = new AtomicBoolean(false);
        ContactService.DiscoveryLookup lookup = targetPeerId -> {
            lookupWasCalled.set(true);
            return CompletableFuture.completedFuture(new DiscoveryLookupResult(false, null));
        };
        ContactService contactService = new ContactService(lookup, storage, routingTable, null);

        String inviteCode = InviteCodeCodec.encode(new InviteCode(alreadyKnown, "/ip4/9.9.9.9/tcp/4001", null));
        ContactAddResult result = contactService.addContact(inviteCode).join();

        assertThat(result).isInstanceOf(ContactAddResult.Added.class);
        Contact returned = ((ContactAddResult.Added) result).contact();
        // The EXISTING record comes back (verified=true preserved) -- not a freshly-constructed,
        // unverified one that would silently downgrade an already-trusted contact.
        assertThat(returned.verified()).isTrue();
        assertThat(returned.displayName()).isEqualTo("Existing Alice");
        assertThat(lookupWasCalled).isFalse();
    }

    @Test
    void peerNotFoundResolvesToFailedWithoutThrowing() {
        ContactService.DiscoveryLookup lookup = targetPeerId ->
                CompletableFuture.completedFuture(new DiscoveryLookupResult(false, null));
        ContactService contactService = new ContactService(lookup, storage, routingTable, null);

        String inviteCode = InviteCodeCodec.encode(new InviteCode(
                new PeerId("12D3KooWNobodyHome11111111111111111111111111111"), "/ip4/9.9.9.9/tcp/4001", null));
        ContactAddResult result = contactService.addContact(inviteCode).join();

        assertThat(result).isInstanceOf(ContactAddResult.Failed.class);
        assertThat(((ContactAddResult.Failed) result).reason()).isEqualTo(ContactAddResult.Reason.PEER_NOT_FOUND);
    }

    @Test
    void aRecordSignedByTheWrongKeyIsRejectedNotSilentlyAccepted() throws Exception {
        Identity claimedPeer = generateIdentity();
        Identity actualSigner = generateIdentity(); // a different keypair entirely

        DiscoveryRecord record = new DiscoveryRecord(
                List.of("/ip4/203.0.113.5/tcp/9000/p2p/" + claimedPeer.peerId()),
                null, null, System.currentTimeMillis() + 60_000);
        // Signed with actualSigner's key, but the invite code claims claimedPeer -- simulates a
        // relay or attacker handing back a record for the wrong identity.
        byte[] wireSignedByWrongKey = DiscoveryRecordCodec.encodeSigned(record, actualSigner.rawPublicKey(), actualSigner.rawPrivateKeySeed());

        ContactService.DiscoveryLookup lookup = targetPeerId ->
                CompletableFuture.completedFuture(new DiscoveryLookupResult(true, wireSignedByWrongKey));
        ContactService contactService = new ContactService(lookup, storage, routingTable, null);

        String inviteCode = InviteCodeCodec.encode(new InviteCode(new PeerId(claimedPeer.peerId()), "/ip4/9.9.9.9/tcp/4001", null));
        ContactAddResult result = contactService.addContact(inviteCode).join();

        assertThat(result).isInstanceOf(ContactAddResult.Failed.class);
        assertThat(((ContactAddResult.Failed) result).reason()).isEqualTo(ContactAddResult.Reason.VERIFICATION_FAILED);
        assertThat(storage.getContact(new PeerId(claimedPeer.peerId()))).isNull();
    }

    @Test
    void malformedInviteCodeResolvesToFailedWithoutTouchingTheNetwork() {
        AtomicBoolean lookupWasCalled = new AtomicBoolean(false);
        ContactService.DiscoveryLookup lookup = targetPeerId -> {
            lookupWasCalled.set(true);
            return CompletableFuture.completedFuture(new DiscoveryLookupResult(false, null));
        };
        ContactService contactService = new ContactService(lookup, storage, routingTable, null);

        ContactAddResult result = contactService.addContact("not a valid invite code!!!").join();

        assertThat(result).isInstanceOf(ContactAddResult.Failed.class);
        assertThat(((ContactAddResult.Failed) result).reason()).isEqualTo(ContactAddResult.Reason.MALFORMED_INVITE_CODE);
        assertThat(lookupWasCalled).isFalse();
    }

    @Test
    void noDiscoveryAddressAnywhereResolvesToFailedWithoutTouchingTheNetwork() {
        AtomicBoolean lookupWasCalled = new AtomicBoolean(false);
        ContactService.DiscoveryLookup lookup = targetPeerId -> {
            lookupWasCalled.set(true);
            return CompletableFuture.completedFuture(new DiscoveryLookupResult(false, null));
        };
        ContactService contactService = new ContactService(lookup, storage, routingTable, null); // no default relay

        String inviteCode = InviteCodeCodec.encode(new InviteCode(
                new PeerId("12D3KooWNoRelay1111111111111111111111111111111"), null, null)); // no 'd' field either
        ContactAddResult result = contactService.addContact(inviteCode).join();

        assertThat(result).isInstanceOf(ContactAddResult.Failed.class);
        assertThat(((ContactAddResult.Failed) result).reason()).isEqualTo(ContactAddResult.Reason.NO_DISCOVERY_SERVER);
        assertThat(lookupWasCalled).isFalse();
    }

    @Test
    void fallsBackToTheDaemonsDefaultRelayWhenTheInviteCodeOmitsItsOwn() throws Exception {
        Identity carol = generateIdentity();
        DiscoveryRecord record = new DiscoveryRecord(List.of(), null, null, System.currentTimeMillis() + 60_000);
        byte[] wire = DiscoveryRecordCodec.encodeSigned(record, carol.rawPublicKey(), carol.rawPrivateKeySeed());

        ContactService.DiscoveryLookup lookup = targetPeerId ->
                CompletableFuture.completedFuture(new DiscoveryLookupResult(true, wire));
        ContactService contactService = new ContactService(lookup, storage, routingTable, "/ip4/1.1.1.1/tcp/4001/p2p/default-relay");

        String inviteCode = InviteCodeCodec.encode(new InviteCode(new PeerId(carol.peerId()), null, null)); // no 'd' field
        ContactAddResult result = contactService.addContact(inviteCode).join();

        assertThat(result).isInstanceOf(ContactAddResult.Added.class);
        // A record with zero published addresses still produces a route -- with a null
        // directMultiaddr, not a crash.
        PeerRoute route = routingTable.get(new PeerId(carol.peerId()));
        assertThat(route).isNotNull();
        assertThat(route.directMultiaddr()).isNull();
    }

    @Test
    void aLookupThatNeverCompletesResolvesToFailedInsteadOfHangingForever() {
        ContactService.DiscoveryLookup lookup = targetPeerId -> new CompletableFuture<>(); // never completes
        ContactService contactService = new ContactService(lookup, storage, routingTable, null, Duration.ofMillis(100));

        String inviteCode = InviteCodeCodec.encode(new InviteCode(
                new PeerId("12D3KooWNeverResponds111111111111111111111111111"), "/ip4/9.9.9.9/tcp/4001", null));

        long start = System.currentTimeMillis();
        ContactAddResult result = contactService.addContact(inviteCode).join();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isInstanceOf(ContactAddResult.Failed.class);
        assertThat(((ContactAddResult.Failed) result).reason()).isEqualTo(ContactAddResult.Reason.LOOKUP_FAILED);
        assertThat(elapsed).isLessThan(2000L); // the injected 100ms timeout actually bounds the wait
    }
}
