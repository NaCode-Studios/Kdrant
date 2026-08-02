import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    // No dokka-javadoc: its generator refuses a multiplatform project, and the publishing plugin
    // fills the required -javadoc.jar with Dokka's HTML output instead. Same reason as kdrant-core.
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    // The same targets kdrant-core compiles for, which is the point: before this module moved out of
    // src/main, the eight native targets got the models and the DSL with nothing to put them on the
    // wire. Ktor's client is one API across engines, so only the engine is chosen per target.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kdrant-core"))

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        // One engine per target family, each behind the same `httpClient` expect declaration.
        //
        // Darwin goes through NSURLSession and inherits App Transport Security: an iOS app reaching a
        // Qdrant over plaintext HTTP is refused by the platform before Kdrant sees the request. Set
        // useTls, or add the ATS exception yourself and mean it.
        //
        // Curl links against libcurl, which has to be present on the host: it ships with macOS and with
        // every mainstream Linux distribution, but a slim container image may not have it.
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.curl)
        }
        mingwMain.dependencies {
            implementation(libs.ktor.client.winhttp)
        }

        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.ktor.client.mock)

            // Integration tests spin up a real Qdrant in Docker. The testkit carries the shared client
            // contract both engines are held to, and brings Testcontainers with it.
            implementation(project(":kdrant-testkit"))
            implementation(project.dependencies.platform(libs.testcontainers.bom))
            implementation(libs.testcontainers.qdrant)
        }
        // The same contract the JVM runs, from a native binary. It needs a Qdrant that is already
        // running (Testcontainers is JVM-only), so it is skipped unless KDRANT_NATIVE_IT names one.
        nativeTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(project(":kdrant-testkit"))
        }
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// The other half of the opt-in argument: the gRPC engine costs a REST user nothing only if a build
// that depends on this module resolves none of it. Checked on every `check` rather than argued in the
// README, because a transitive dependency added later would make the claim quietly false and the
// footprint table in the README rests on it. The JVM classpath is the one that could regress — the
// native targets cannot resolve grpc-java at all.
val forbiddenGroups = setOf("io.grpc", "com.google.protobuf", "io.netty", "com.google.guava")
val runtimeArtifacts = configurations.named("jvmRuntimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }

val verifyRestEngineFootprint by tasks.registering {
    description = "Fails if the REST engine's runtime classpath resolves gRPC, protobuf, Netty or Guava."
    val artifacts = runtimeArtifacts
    val groups = forbiddenGroups
    doLast {
        val found = artifacts.get()
            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .filter { it.group in groups }
            .map { "${it.group}:${it.module}:${it.version}" }
            .sorted()
        require(found.isEmpty()) {
            "the REST engine must not resolve the gRPC stack, and it resolved:\n" + found.joinToString("\n") { "  $it" }
        }
    }
}

tasks.named("check") { dependsOn(verifyRestEngineFootprint) }

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// A Kotlin/Native test binary is launched by Gradle rather than inheriting a shell, so the two
// variables that point the native client contract at a running Qdrant have to be handed over
// explicitly. Blank means "not set", and the contract skips itself rather than failing, which is what
// keeps `./gradlew build` green on a laptop with no Qdrant on it.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KDRANT_QDRANT_HOST", providers.environmentVariable("KDRANT_QDRANT_HOST").getOrElse(""))
    environment("KDRANT_QDRANT_PORT", providers.environmentVariable("KDRANT_QDRANT_PORT").getOrElse(""))
    // Set by the CI jobs that start a Qdrant. It turns the skip into a failure, so a broken hand-over
    // reports red instead of reporting green for having proven nothing.
    environment("KDRANT_QDRANT_REQUIRED", providers.environmentVariable("KDRANT_QDRANT_REQUIRED").getOrElse(""))
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.nacode-studios", "kdrant-transport-rest", version.toString())
    pom {
        name.set("Kdrant REST transport")
        description.set(
            "Default REST/Ktor engine for Kdrant, the coroutine-first Kotlin client for the Qdrant " +
                "vector database: a small, pure-Kotlin HTTP transport with no gRPC, Netty, or protobuf. " +
                "This is the module to depend on to use Kdrant. Runs on the JVM, iOS, macOS, Linux and " +
                "Windows.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/NaCode-Studios/Kdrant")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("NaCode-Studios")
                name.set("NaCode Studios")
                url.set("https://github.com/NaCode-Studios")
            }
        }
        scm {
            url.set("https://github.com/NaCode-Studios/Kdrant")
            connection.set("scm:git:https://github.com/NaCode-Studios/Kdrant.git")
            developerConnection.set("scm:git:ssh://git@github.com/NaCode-Studios/Kdrant.git")
        }
    }
}
