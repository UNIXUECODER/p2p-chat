plugins {
    id("java-library")
}

// PeerId appears in DiscoveryRecordCodec.verifyAndDecode's public signature, so this must be
// api, not implementation — same rule core-storage and core-network already follow (see their
// build files).
//
// No dependency on core-network (jvm-libp2p) or core-identity: the Ed25519-to-peer-ID
// derivation in Ed25519RecordKeys is a from-scratch, pure-JDK implementation of the libp2p
// peer-id spec, not a call into jvm-libp2p's own PeerId class — see that class's javadoc for
// why, and how it was verified without the ability to compile against the real library. Key
// material is accepted as raw byte arrays rather than an IdentityService/Identity type, for
// the same reason PreKeyBundleFactory and PeerNetworkService.start() already do.
//
// No dependency on core-crypto either: preKeyBundle travels as opaque bytes on DiscoveryRecord
// (the caller is expected to encode/decode it via core-crypto's PreKeyBundleCodec before/after
// touching this module), the same "don't interpret the payload" boundary DiscoveryRegistry
// already established for the wire protocol layer.
dependencies {
    api(project(":core-model"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.0")
}

tasks.test {
    useJUnitPlatform()
}
