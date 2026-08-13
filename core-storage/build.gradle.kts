plugins {
    id("java-library")
}

dependencies {
    // PeerId/DeviceId appear as record components on this module's own public types
    // (com.p2pchat.storage.model.Message, .Contact), so this must be api, not implementation,
    // per the same rule core-network and core-crypto already follow (see their build files).
    api(project(":core-model"))

    // SQLite JDBC driver. Apache 2.0 licensed — unlike core-crypto's libsignal-client
    // dependency, no AGPL consideration applies here. Pure-Java-facing API (java.sql.*),
    // backed by a bundled native library per platform. On Maven Central, so no extra
    // repository entries are needed beyond what the root build.gradle.kts already declares.
    // Nothing in this module's public API (StorageService, the storage.model records)
    // exposes an org.sqlite.* type — java.sql.Connection is a JDK type, not driver-specific —
    // so this stays implementation, not api.
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
}
