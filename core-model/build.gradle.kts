plugins {
    java
}

// core-model: zero external dependencies, and deliberately zero project dependencies too.
//
// This is the shared value-type layer (PeerId, DeviceId today) referenced by nearly every
// other module — core-network's callback interfaces, core-storage's schema-backed records,
// and eventually core-identity/core-crypto's call sites. Because it's a dependency of nearly
// everything, it must not depend on any of them (or on any external library), or it becomes
// an accidental integration point that couples unrelated concerns together. See
// docs/architecture-spec.md §4 (domain model) — PeerId/DeviceId were already specified there
// as plain records before any module needed them enough to justify actually scaffolding this.
//
// Added in M3d. See the M3d section of README.md for what this module does and does not fix.
