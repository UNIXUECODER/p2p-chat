package com.p2pchat.network;

/** Explicit result of a discovery lookup — found-with-data or genuinely not-found, never ambiguous. */
public record DiscoveryLookupResult(boolean found, byte[] payload) {
}
