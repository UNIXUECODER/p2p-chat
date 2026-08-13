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

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.0")
}

tasks.test {
    useJUnitPlatform()
}
