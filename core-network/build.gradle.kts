plugins {
    id("java-library")
}

dependencies {
    api(project(":core-model"))
    api("com.github.libp2p:jvm-libp2p:1.3.4")
    implementation("io.netty:netty-codec-http:4.2.10.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}

// No SLF4J binding is declared — jvm-libp2p will print a one-line "no SLF4J
// providers found" warning at startup, which is harmless. Add a binding
// (e.g. log4j-slf4j2-impl) later if you want its internal logs surfaced.
