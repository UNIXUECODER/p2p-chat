allprojects {
    group = "com.p2pchat"
    version = "0.1.0"

    repositories {
        mavenCentral()
        // jvm-libp2p is not published to Maven Central — these three are required for core-network.
        maven { url = uri("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/") }
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://artifacts.consensys.net/public/maven/maven/") }
        // libsignal-client (real Signal Protocol / PQXDH implementation) is also not on
        // Maven Central — required for core-crypto. AGPL-3.0 licensed; see docs/architecture-spec.md §8.
        maven { url = uri("https://build-artifacts.signal.org/libraries/maven/") }
    }
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testImplementation"("org.assertj:assertj-core:3.25.3")
        "testImplementation"("org.mockito:mockito-core:5.11.0")
        "testImplementation"("org.mockito:mockito-junit-jupiter:5.11.0")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
