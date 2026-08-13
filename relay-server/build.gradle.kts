plugins {
    java
    application
}

dependencies {
    implementation(project(":core-identity"))
    implementation(project(":core-model"))
    implementation(project(":core-network"))
}

application {
    mainClass.set("com.p2pchat.relay.RelayServerMain")
}
