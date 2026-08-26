plugins {
    java
    application
}

dependencies {
    implementation(project(":core-identity"))
    implementation(project(":core-model"))
    implementation(project(":core-network"))
    // Used only to peek at a well-formed record's expiresAt field (DiscoveryRegistry never
    // verifies signatures — see that class's javadoc for why that's not this module's job).
    implementation(project(":core-discovery"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.0")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.p2pchat.relay.RelayServerMain")
}
