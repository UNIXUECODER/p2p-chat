plugins {
    java
}

// No external dependencies yet — Ed25519 key generation is built into the
// JDK (java.security.KeyPairGenerator) since Java 15, so M0 needs nothing
// beyond the standard library. Bouncy Castle / libsignal get added in M2
// when core-crypto lands (see docs/architecture-spec.md §16).
