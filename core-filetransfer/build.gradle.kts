plugins {
    java
}

// core-filetransfer (M4a): zero dependencies, deliberately. Chunking and per-chunk AES-256-GCM
// encryption need nothing beyond the JDK itself (javax.crypto, java.security.MessageDigest,
// java.nio.file) — no reason to pull in an external crypto or IO library for this.
//
// Verified by actually compiling and running this module's logic (not just core-network's
// existing patterns traced by hand) — see the M4a section of README.md.
