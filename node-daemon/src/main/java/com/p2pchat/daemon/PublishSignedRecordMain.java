package com.p2pchat.daemon;

import com.p2pchat.crypto.PreKeyBundleCodec;
import com.p2pchat.crypto.PreKeyBundleFactory;
import com.p2pchat.crypto.SignalIdentity;
import com.p2pchat.crypto.SignalIdentityVault;
import com.p2pchat.discovery.DiscoveryRecord;
import com.p2pchat.discovery.DiscoveryRecordCodec;
import com.p2pchat.discovery.Ed25519RecordKeys;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;

import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * M6f: publishes a signed DiscoveryRecordV2 — real addresses, a real PreKeyBundle, and a real
 * Ed25519 signature over both — to a discovery server. A new, separate demo alongside M3c's
 * {@code PublishRecordMain} (which publishes plain, unsigned address bytes and is left
 * untouched), for the same "don't quarantine a proven regression tool" reason
 * {@code SessionManagerListenerMain} was added alongside {@code ChatListenerMain} rather than
 * replacing it.
 *
 * <p><b>What this deliberately does not solve:</b> M6e-2 testing hit a real, concrete instance
 * of pre-key bundle staleness — a static bundle published once at startup, never regenerated,
 * so a later sender reads the same already-consumed one-time prekey an earlier sender already
 * used. That's a live-daemon-loop problem (deciding when/how often to regenerate and republish),
 * and this demo doesn't have a daemon loop to hang that policy off of — that's M6g/M6h's job,
 * once a real running daemon exists. What this demo does prove: the record FORMAT itself is not
 * what's standing in the way of fixing that. {@code DiscoveryController.publish()} is a plain
 * overwrite (see {@code DiscoveryRegistry}'s own "overwriteExistingRecord" test) — republishing
 * a fresh signed record, on whatever cadence eventually drives it, is just calling this same
 * code again with a fresh bundle. Not claiming otherwise here.
 */
public class PublishSignedRecordMain {

    private static final Duration TTL = Duration.ofMinutes(5);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: ./gradlew :node-daemon:runPublishSignedRecord "
                    + "-Pdiscovery=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\" [-Pport=9000]");
            return;
        }
        String discoveryAddress = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        byte[] rawPrivateKeySeed = identityService.rawPrivateKeySeed();
        byte[] rawPublicKey = Ed25519RecordKeys.rawPublicKeyFromX509(identity.publicKey());

        // Fresh pre-keys every run, same as SecureListenerMain's already-verified pattern —
        // only the core identity is persistent (SignalIdentityVault), not this store.
        SignalIdentity signalIdentity = SignalIdentityVault.loadOrCreate(baseDir);
        InMemorySignalProtocolStore signalStore =
                new InMemorySignalProtocolStore(signalIdentity.keyPair(), signalIdentity.registrationId());
        PreKeyBundle bundle = PreKeyBundleFactory.create(signalStore);
        byte[] bundleBytes = PreKeyBundleCodec.encode(bundle);

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(port, rawPrivateKeySeed, (sender, data) -> {
            String text = new String(data, StandardCharsets.UTF_8);
            System.out.println("[envelope] received from " + sender + ": \"" + text + "\"");
        }, new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }
            @Override public void onFrame(PeerId sender, RelayFrame frame) { }
        }, new DiscoveryRequestHandler() {
            @Override public void onPublish(PeerId publisher, byte[] payload) { }
            @Override public byte[] onLookup(String targetPeerId) { return null; }
        });

        List<String> myAddresses = List.of(network.listenAddresses());
        long expiresAt = System.currentTimeMillis() + TTL.toMillis();
        DiscoveryRecord record = new DiscoveryRecord(myAddresses, bundleBytes, null, expiresAt);
        byte[] signedWire = DiscoveryRecordCodec.encodeSigned(record, rawPublicKey, rawPrivateKeySeed);

        DiscoveryController discovery = network.connectToDiscovery(discoveryAddress);
        discovery.publish(signedWire);

        // Free sanity check: the libp2p peer ID this real running host derived for itself
        // (embedded in its own listen addresses) should match what Ed25519RecordKeys
        // independently derives from the same raw public key. If it doesn't, something is wrong
        // with either the running libp2p host or this milestone's peer-ID derivation — this is
        // exactly the live cross-check against the real library that couldn't be run inside the
        // build sandbox this was developed in (no Maven Central access there), only verified
        // against the official spec vector and an independent implementation. Run this and
        // confirm "matches: true" once, on a machine that can actually build jvm-libp2p.
        String actualPeerId = myAddresses.isEmpty() ? null
                : myAddresses.get(0).substring(myAddresses.get(0).lastIndexOf("/p2p/") + 5);
        String derivedPeerId = Ed25519RecordKeys.peerIdFromRawPublicKey(rawPublicKey);

        System.out.println("App identity     : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("libp2p peer id   : " + actualPeerId);
        System.out.println("Derived peer id  : " + derivedPeerId + "  (matches: " + derivedPeerId.equals(actualPeerId) + ")");
        System.out.println();
        System.out.println("Published a SIGNED record (" + signedWire.length + " bytes) to " + discoveryAddress);
        System.out.println("  addresses  : " + myAddresses.size());
        System.out.println("  bundle     : " + bundleBytes.length + " bytes (real PreKeyBundle)");
        System.out.println("  expires in : " + TTL.toMinutes() + " minute(s)");
        System.out.println();
        System.out.println("Run LookupSignedRecordMain against " + actualPeerId + " from another terminal to verify it.");
        System.out.println("Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }
}
