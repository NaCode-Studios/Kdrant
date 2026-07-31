import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":kdrant-core"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // Integration tests spin up a real Qdrant in Docker. The testkit carries the shared client
    // contract both engines are held to, and brings JUnit and Testcontainers with it.
    testImplementation(project(":kdrant-testkit"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.qdrant)
}

// The other half of the opt-in argument: the gRPC engine costs a REST user nothing only if a build
// that depends on this module resolves none of it. Checked on every `check` rather than argued in the
// README, because a transitive dependency added later would make the claim quietly false and the
// footprint table in the README rests on it.
val forbiddenGroups = setOf("io.grpc", "com.google.protobuf", "io.netty", "com.google.guava")
val runtimeArtifacts = configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }

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

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.nacode-studios", "kdrant-transport-rest", version.toString())
    pom {
        name.set("Kdrant REST transport")
        description.set(
            "Default REST/Ktor engine for Kdrant, the coroutine-first Kotlin client for the Qdrant " +
                "vector database — a small, pure-Kotlin HTTP transport with no gRPC, Netty, or protobuf. " +
                "This is the module to depend on to use Kdrant.",
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
