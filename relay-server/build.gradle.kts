plugins {
    java
    application
}

dependencies {
    implementation(project(":core-identity"))
    implementation(project(":core-model"))
    implementation(project(":core-network"))

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
