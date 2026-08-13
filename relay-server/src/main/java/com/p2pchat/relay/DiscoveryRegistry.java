package com.p2pchat.relay;

import com.p2pchat.network.DiscoveryRequestHandler;
import com.p2pchat.model.PeerId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The relay server's discovery logic: stores each publisher's record (opaque
 * bytes — this module doesn't need to know or care what's inside; that's
 * the publishing peer's concern, e.g. core-crypto's PreKeyBundleCodec for a
 * future bundle-discovery extension) and answers lookups against it.
 */
public class DiscoveryRegistry implements DiscoveryRequestHandler {

    private final Map<String, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public void onPublish(PeerId publisher, byte[] payload) {
        records.put(publisher.toString(), payload);
        System.out.println("[discovery] published: " + publisher + " (" + payload.length + " bytes, "
                + records.size() + " record(s) on file)");
    }

    @Override
    public byte[] onLookup(String targetPeerId) {
        byte[] result = records.get(targetPeerId);
        System.out.println("[discovery] lookup for " + targetPeerId + ": " + (result != null ? "found" : "not found"));
        return result;
    }
}
