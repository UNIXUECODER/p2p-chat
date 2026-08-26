package com.p2pchat.daemon;

import com.p2pchat.discovery.DiscoveryRecord;
import com.p2pchat.discovery.DiscoveryRecordCodec;
import com.p2pchat.discovery.DiscoveryRecordException;
import com.p2pchat.identity.Identity;
import com.p2pchat.identity.IdentityService;
import com.p2pchat.identity.JavaIdentityService;
import com.p2pchat.model.PeerId;
import com.p2pchat.network.DiscoveryController;
import com.p2pchat.network.DiscoveryLookupResult;
import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.network.Libp2pNetworkService;
import com.p2pchat.network.PeerNetworkService;
import com.p2pchat.network.RelayController;
import com.p2pchat.network.RelayEventHandler;
import com.p2pchat.network.RelayFrame;

import java.nio.file.Path;
import java.time.Instant;

/**
 * M6f: looks up a peer's signed DiscoveryRecordV2 and — the actual point of this milestone —
 * verifies it before trusting anything in it, via {@link DiscoveryRecordCodec#verifyAndDecode}.
 * Counterpart to {@link PublishSignedRecordMain}; M3c's {@code LookupPeerMain} is left
 * untouched for looking up plain unsigned records.
 *
 * <p>Try pointing this at a target peer ID that never published a signed record (only
 * {@code PublishRecordMain}'s plain bytes, or nothing) to see the MALFORMED path; there's no
 * way to manufacture a PEER_ID_MISMATCH or BAD_SIGNATURE from the command line without a
 * cooperating malicious publisher, which is exactly why {@code DiscoveryRecordCodecTest}
 * exercises those two paths directly instead.
 */
public class LookupSignedRecordMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ./gradlew :node-daemon:runLookupSignedRecord \\");
            System.out.println("           -Pdiscovery=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\" \\");
            System.out.println("           -Ptarget=\"<libp2p peer ID to look up>\"");
            return;
        }
        String discoveryAddress = args[0];
        String targetPeerId = args[1];

        Path baseDir = Path.of(System.getProperty("user.dir"), System.getProperty("p2pchat.dataDir", ".p2p-chat-data"));
        IdentityService identityService = new JavaIdentityService(baseDir);
        Identity identity = identityService.hasIdentity()
                ? identityService.loadIdentity()
                : identityService.createIdentity("anonymous");

        PeerNetworkService network = new Libp2pNetworkService();
        network.start(0, identityService.rawPrivateKeySeed(), (sender, data) -> { }, new RelayEventHandler() {
            @Override public void onConnected(PeerId peerId, RelayController controller) { }
            @Override public void onFrame(PeerId sender, RelayFrame frame) { }
        }, new DiscoveryRequestHandler() {
            @Override public void onPublish(PeerId publisher, byte[] payload) { }
            @Override public byte[] onLookup(String requestedPeerId) { return null; }
        });

        System.out.println("App identity : " + identity.peerId() + " (\"" + identity.displayName() + "\")");
        System.out.println("Looking up " + targetPeerId + " via discovery server at " + discoveryAddress + "...");

        DiscoveryController discovery = network.connectToDiscovery(discoveryAddress);
        DiscoveryLookupResult result = discovery.lookup(targetPeerId).get();

        if (!result.found()) {
            System.out.println();
            System.out.println("Not found — they haven't published a record with this discovery server, or it's expired "
                    + "(DiscoveryRegistry withholds expired records at lookup time as of M6f).");
            network.stop();
            return;
        }

        try {
            DiscoveryRecord record = DiscoveryRecordCodec.verifyAndDecode(
                    result.payload(), PeerId.of(targetPeerId), System.currentTimeMillis());

            System.out.println();
            System.out.println("Found and VERIFIED — real Ed25519 signature checked against this exact peer ID, not just trusted:");
            System.out.println("  addresses:");
            for (String addr : record.addresses()) {
                System.out.println("    " + addr);
            }
            System.out.println("  pre-key bundle : " + (record.hasPreKeyBundle()
                    ? record.preKeyBundle().length + " bytes (would feed a real session handshake)"
                    : "none published"));
            System.out.println("  relay pref     : " + (record.hasRelayMultiaddr() ? record.relayMultiaddr() : "none"));
            System.out.println("  expires at     : " + Instant.ofEpochMilli(record.expiresAt()));
        } catch (DiscoveryRecordException e) {
            System.out.println();
            System.out.println("Found a record, but REJECTED it: " + e.reason() + " — " + e.getMessage());
            System.out.println("That rejection is the point of this milestone — a tampered or substituted record");
            System.out.println("should end up exactly here, not silently accepted.");
        }

        network.stop();
    }
}
