rootProject.name = "p2p-chat"

include("core-identity")
include("core-model")
include("core-network")
include("core-crypto")
include("core-storage")
include("core-filetransfer")
include("core-messaging")
include("relay-server")
include("node-daemon")

// core-model (M3d): shared value types (PeerId, DeviceId) used across module boundaries.
// Not in the architecture spec's original §3 module list — introduced when core-network's
// public API needed to stop leaking io.libp2p.core.PeerId and core-storage needed a real
// type for peer_id columns. See the M3d section of README.md.

// core-storage (M3d): SQLite persistence layer + migrations, per docs/architecture-spec.md §9.
// Scaffolded ahead of M4 (file transfer) because "resumable" transfer state has to survive a
// restart, which means it can't be designed against in-memory state first. See README.md.

// core-filetransfer (M4a): chunking + per-chunk AES-256-GCM encryption, proven in isolation.
// See the M4a section of README.md for what's implemented here vs. deferred to M4b/M4c.

// core-messaging (M5a): HybridLogicalClock, proven in isolation. Real 1:1 send/receive wiring
// lands here incrementally across M5b/M5c/M5d — see the M5a section of README.md.

// Modules from the architecture spec not yet scaffolded — added as each
// milestone is reached, per docs/architecture-spec.md §17:
// include("core-discovery")
// include("core-groups")
