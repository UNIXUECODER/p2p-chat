plugins {
    java
    application
}

dependencies {
    implementation(project(":core-identity"))
    implementation(project(":core-network"))
    implementation(project(":core-crypto"))
    implementation(project(":core-storage"))
    implementation(project(":core-filetransfer"))
    implementation(project(":core-messaging"))
}

application {
    mainClass.set("com.p2pchat.daemon.Main")   // M0 identity demo — unchanged
}

// M1 demos — separate tasks so M0's `run` task keeps working exactly as before.
tasks.register<JavaExec>("runListener") {
    group = "p2p-chat"
    description = "M1: starts a libp2p node bound to your persistent identity, and listens for an incoming ping. " +
            "Optional: -Pport=9000 -Pdatadir=.p2p-chat-data (use a different -Pdatadir per instance " +
            "if running two on the same machine, so they don't share one identity)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.NetworkListenerMain")
    if (project.hasProperty("port")) {
        args = listOf(project.property("port") as String)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

tasks.register<JavaExec>("runPinger") {
    group = "p2p-chat"
    description = "M1: starts a libp2p node bound to your persistent identity, and pings a peer. " +
            "Required: -Paddr=\"/ip4/<ip>/tcp/<port>/p2p/<peer-id>\" (printed by runListener). " +
            "Optional: -Pdatadir=.p2p-chat-data (see runListener note)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.NetworkPingerMain")
    if (project.hasProperty("addr")) {
        args = listOf(project.property("addr") as String)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M2a demo — no networking involved yet. Two in-memory identities (Alice, Bob)
// establish a real PQXDH session and exchange Double-Ratchet-encrypted messages,
// entirely locally, to prove the crypto itself is correct before any wire format
// or real peer connection touches it.
tasks.register<JavaExec>("runCryptoDemo") {
    group = "p2p-chat"
    description = "M2a: proves PQXDH session establishment + Double Ratchet encrypt/decrypt " +
            "work correctly, using two in-memory identities. No networking involved."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.CryptoDemoMain")
}

// M2c — everything from M1 (network), M1.5 (persistent identity), M2a (crypto),
// and M2b (custom protocol) working together: two real peers, over a real
// connection, exchanging an actual PQXDH/Double-Ratchet-encrypted message.
tasks.register<JavaExec>("runSecureListener") {
    group = "p2p-chat"
    description = "M2c: listens for a real encrypted message (PQXDH + Double Ratchet) over a real " +
            "connection. Optional: -Pport=9000 -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.SecureListenerMain")
    if (project.hasProperty("port")) {
        args = listOf(project.property("port") as String)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

tasks.register<JavaExec>("runSecureSender") {
    group = "p2p-chat"
    description = "M2c: establishes a PQXDH session and sends one real encrypted message. " +
            "Required: -Paddr=\"...\" -Pbundlefile=\"...\". Optional: -Pmessage=\"...\" -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.SecureSenderMain")
    val addr = project.findProperty("addr") as String?
    val bundleFile = project.findProperty("bundlefile") as String?
    val message = (project.findProperty("message") as String?) ?: "Hello \u2014 this is an M2c secure test message."
    if (addr != null && bundleFile != null) {
        args = listOf(addr, bundleFile, message)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M3a — a peer with no direct connection to another peer can still reach them,
// via a relay server both can reach. Proves the core relay mechanism in
// isolation, before any "try direct first" fallback logic (M3b) is added.
tasks.register<JavaExec>("runRelayRegister") {
    group = "p2p-chat"
    description = "M3a: connects to a relay and stays connected, printing any message delivered through it. " +
            "Required: -Prelay=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\". Optional: -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.RelayRegisterMain")
    if (project.hasProperty("relay")) {
        args = listOf(project.property("relay") as String)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

tasks.register<JavaExec>("runRelayForward") {
    group = "p2p-chat"
    description = "M3a: asks a relay to forward one message to a target peer with no direct connection. " +
            "Required: -Prelay=\"...\" -Ptarget=\"<libp2p peer ID from runRelayRegister>\". " +
            "Optional: -Pmessage=\"...\" -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.RelayForwardMain")
    val relay = project.findProperty("relay") as String?
    val target = project.findProperty("target") as String?
    val message = (project.findProperty("message") as String?) ?: "Hello \u2014 this is an M3a relay test message."
    if (relay != null && target != null) {
        args = listOf(relay, target, message)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M3b — direct-first connection strategy: tries a direct connection, falls
// back to relay only if that fails or times out, surfacing which path was
// actually used (ConnectivityStatus) rather than assuming.
tasks.register<JavaExec>("runReachPeer") {
    group = "p2p-chat"
    description = "M3b: tries a direct connection first (3s timeout), falls back to relay if needed. " +
            "Required: -Prelay=\"...\" -Ptarget=\"...\". Optional: -Pdirectaddr=\"...\" (blank/omitted = " +
            "skip straight to relay) -Pmessage=\"...\" -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.ReachPeerMain")
    val directAddr = (project.findProperty("directaddr") as String?) ?: ""
    val relay = project.findProperty("relay") as String?
    val target = project.findProperty("target") as String?
    val message = (project.findProperty("message") as String?) ?: "Hello \u2014 this is an M3b reachability test message."
    if (relay != null && target != null) {
        args = listOf(directAddr, relay, target, message)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M3c — real discovery: publish this peer's address(es) to a discovery
// server (co-located with relay-server), and look another peer's up,
// replacing the manual multiaddr hand-carrying used everywhere from M1
// through M3b.
tasks.register<JavaExec>("runPublishRecord") {
    group = "p2p-chat"
    description = "M3c: listens directly AND publishes its address(es) to a discovery server. " +
            "Required: -Pdiscovery=\"/ip4/<ip>/tcp/<port>/p2p/<relay-peer-id>\". Optional: -Pport=9000 -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.PublishRecordMain")
    val discovery = project.findProperty("discovery") as String?
    val port = (project.findProperty("port") as String?) ?: "9000"
    if (discovery != null) {
        args = listOf(discovery, port)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

tasks.register<JavaExec>("runLookupPeer") {
    group = "p2p-chat"
    description = "M3c: looks up another peer's published record via the discovery server. " +
            "Required: -Pdiscovery=\"...\" -Ptarget=\"<libp2p peer ID from runPublishRecord>\". Optional: -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.LookupPeerMain")
    val discoveryArg = project.findProperty("discovery") as String?
    val targetArg = project.findProperty("target") as String?
    if (discoveryArg != null && targetArg != null) {
        args = listOf(discoveryArg, targetArg)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M3d — no networking, no crypto: opens (and migrates) a SQLite database and round-trips a
// contact, a message, and file-transfer metadata through StorageService, proving the schema
// (architecture-spec.md §9), the migration runner, and the JDBC wiring all work together
// before M4 builds resumable file transfer on top of the file_transfers/file_chunk_state
// tables this already creates.
tasks.register<JavaExec>("runStorageDemo") {
    group = "p2p-chat"
    description = "M3d: opens (and migrates) the SQLite database and round-trips a contact, a message, " +
            "and file-transfer metadata through StorageService. Optional: -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.StorageDemoMain")
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M4d — no networking, no crypto: proves chunk-level resume against a real SQLite database
// with a genuinely simulated process restart (a SqliteDatabase is closed, then a completely
// new instance is opened against the same directory), same "prove it in isolation" pattern
// every prior milestone piece used.
tasks.register<JavaExec>("runChunkResumeDemo") {
    group = "p2p-chat"
    description = "M4d: proves chunk-level resume survives a simulated restart, against a real (temp) SQLite database."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.ChunkResumeDemoMain")
}

// M4a — no networking, no crypto sessions, no storage: chunks a small local file, encrypts each
// chunk with a fresh AES-256-GCM key, decrypts and reassembles it, verifies the reassembled
// SHA-256 matches, and proves tamper detection by flipping one ciphertext bit and confirming
// decryption fails closed. Implements docs/architecture-spec.md §12 steps 1, 2, and 5 in
// isolation, same pattern as M2a's runCryptoDemo.
tasks.register<JavaExec>("runFileTransferDemo") {
    group = "p2p-chat"
    description = "M4a: chunks, encrypts, decrypts, and verifies a small demo file, including a deliberate tamper-detection check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.FileTransferDemoMain")
}

// M4b — no networking, no crypto sessions: encode/decode round-trip checks for the three
// file-transfer wire payloads (FileOfferPayload, FileChunkRequestPayload, FileChunkPayload),
// same "prove the codec standalone before wiring it up" reasoning M3a's RelayFrameCodec/
// DiscoveryFrameCodec already used.
tasks.register<JavaExec>("runWireCodecDemo") {
    group = "p2p-chat"
    description = "M4b: encode/decode round-trip checks (21 assertions) for the file-transfer wire payloads."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.WireCodecDemoMain")
}

// M4c — the first real, single-peer file transfer over an actual encrypted connection.
// Run runFileReceiver FIRST — it needs no prior knowledge of the sender (see FileOfferPayload's
// Javadoc for why), so it's genuinely safe to be the first thing you start, unlike an earlier
// version of this task that required -Psenderaddr up front.
tasks.register<JavaExec>("runFileReceiver") {
    group = "p2p-chat"
    description = "M4c/M4d: receiving side. Optional: -Pport=9100 " +
            "-Pexitafter=<chunk index, simulates a crash for testing resume> -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.FileReceiverMain")
    val portArg = (project.findProperty("port") as String?) ?: "9100"
    val exitAfterArg = project.findProperty("exitafter") as String?
    args = if (exitAfterArg != null) {
        listOf(portArg, exitAfterArg)
    } else {
        listOf(portArg)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

tasks.register<JavaExec>("runFileSender") {
    group = "p2p-chat"
    description = "M4c/M4d: sending side. Required: -Paddr=\"...\" -Pbundlefile=\"...\" -Pfile=\"...\". " +
            "Optional: -Pport=9000 -Pchunksize=<bytes, for testing resume with a small file> -Pdatadir=.p2p-chat-data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.FileSenderMain")
    val addrArg = project.findProperty("addr") as String?
    val bundlefileArg = project.findProperty("bundlefile") as String?
    val fileArg = project.findProperty("file") as String?
    // Positional args in FileSenderMain — if chunksize (index 4) is supplied, port (index 3)
    // must be too, even if just its own default, or listOfNotNull would silently shift
    // chunksize's value into port's slot.
    val portArg = (project.findProperty("port") as String?) ?: "9000"
    val chunksizeArg = project.findProperty("chunksize") as String?
    if (addrArg != null && bundlefileArg != null && fileArg != null) {
        args = if (chunksizeArg != null) {
            listOf(addrArg, bundlefileArg, fileArg, portArg, chunksizeArg)
        } else {
            listOf(addrArg, bundlefileArg, fileArg, portArg)
        }
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

// M5a — no networking, no crypto, no storage: proves HybridLogicalClock's two event procedures
// (Kulkarni et al. Figure 4), causality across a chain of nodes, the drift/reset property, and
// thread safety under real concurrent load. Added after M5a itself shipped — every other
// milestone from M0 through M4d has exactly this kind of demo, and M5a initially didn't; see
// HlcDemoMain's own Javadoc and the M5a section of README.md.
tasks.register<JavaExec>("runHlcDemo") {
    group = "p2p-chat"
    description = "M5a: proves HybridLogicalClock's send/receive algorithm, causality, drift/reset, and thread safety."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.HlcDemoMain")
}

// M5b — no networking, no crypto sessions: encode/decode round-trip checks for the three chat
// wire payloads (ChatMessagePayload, DeliveryReceiptPayload, ReadReceiptPayload), same "prove
// the codec standalone before wiring it up" reasoning M4b's runWireCodecDemo already used.
tasks.register<JavaExec>("runChatWireCodecDemo") {
    group = "p2p-chat"
    description = "M5b: encode/decode round-trip checks for the three chat wire payloads, including validation."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.ChatWireCodecDemoMain")
}

// M5c — the first real, bidirectional 1:1 chat exchange over an actual encrypted connection,
// persisted via core-storage. Run runChatListener FIRST — it needs no prior knowledge of the
// sender (ChatMessagePayload.senderAddress, added this milestone; see its own Javadoc for why),
// so it's genuinely safe to be the first thing you start, matching FileReceiverMain's own
// pattern.
tasks.register<JavaExec>("runChatListener") {
    group = "p2p-chat"
    description = "M5c/M5d: listening/replying side. Optional: -Pport=9200 -Pdatadir=.p2p-chat-data " +
            "-Preply=\"...\" (the automatic reply text sent back to whoever messages this listener) " +
            "-Pmarkread=true (also sends a read receipt for each message received, in addition to the automatic delivery receipt)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.ChatListenerMain")
    val portArg = (project.findProperty("port") as String?) ?: "9200"
    val replyArg = project.findProperty("reply") as String?
    val markreadArg = project.findProperty("markread") as String?
    // Positional args — if markread (index 2) is supplied, reply (index 1) must be too, even if
    // just its own default, or listOfNotNull would silently shift markread's value into reply's
    // slot. Same reasoning FileSenderMain's chunksize/port pairing already documents above.
    args = if (markreadArg != null) {
        listOf(portArg, replyArg ?: "Hello back \u2014 this is an M5c automatic reply.", markreadArg)
    } else if (replyArg != null) {
        listOf(portArg, replyArg)
    } else {
        listOf(portArg)
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}

tasks.register<JavaExec>("runChatSender") {
    group = "p2p-chat"
    description = "M5c/M5d: sending side. Required: -Paddr=\"...\" -Pbundlefile=\"...\" -Pmessage=\"...\". " +
            "Optional: -Pport=9201 (this node's own listening port, needed to receive the reply back) -Pdatadir=.p2p-chat-data " +
            "-Pmarkread=true (also sends a read receipt for the listener's reply once received, in addition to the automatic delivery receipt) " +
            "-Pduplicatesend=true (sends the same messageId a second time, to exercise the listener's dedup path \u2014 pre-M6 cleanup pass)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.p2pchat.daemon.ChatSenderMain")
    val addrArg = project.findProperty("addr") as String?
    val bundlefileArg = project.findProperty("bundlefile") as String?
    val messageArg = (project.findProperty("message") as String?) ?: "Hello \u2014 this is an M5c chat test message."
    val portArg = (project.findProperty("port") as String?) ?: "9201"
    val markreadArg = project.findProperty("markread") as String?
    val duplicatesendArg = project.findProperty("duplicatesend") as String?
    // Positional args — if duplicatesend (index 5) is supplied, markread (index 4) must be too,
    // even if just "false", or listOfNotNull would silently shift duplicatesend's value into
    // markread's slot. Same reasoning documented on runChatListener/FileSenderMain above.
    if (addrArg != null && bundlefileArg != null) {
        args = if (duplicatesendArg != null) {
            listOf(addrArg, bundlefileArg, messageArg, portArg, markreadArg ?: "false", duplicatesendArg)
        } else if (markreadArg != null) {
            listOf(addrArg, bundlefileArg, messageArg, portArg, markreadArg)
        } else {
            listOf(addrArg, bundlefileArg, messageArg, portArg)
        }
    }
    systemProperty("p2pchat.dataDir", (project.findProperty("datadir") as String?) ?: ".p2p-chat-data")
}
