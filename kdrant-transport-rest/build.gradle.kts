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
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // Integration tests spin up a real Qdrant in Docker.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.qdrant)
}

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

// Secondary distribution: GitHub Packages (Maven Central remains the primary registry).
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/NaCode-Studios/Kdrant")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
