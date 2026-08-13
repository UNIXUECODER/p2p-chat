plugins {
    id("java-library")
}

dependencies {
    // Real Signal Protocol implementation (PQXDH + Double Ratchet). AGPL-3.0 licensed —
    // see docs/architecture-spec.md §8 for the licensing decision and the plan to
    // potentially replace this with an original implementation before any public
    // distribution. Declared as `api` (not `implementation`) so consumers of
    // core-crypto — like node-daemon's M2a demo — can use its types directly.
    api("org.signal:libsignal-client:0.94.0")
}
