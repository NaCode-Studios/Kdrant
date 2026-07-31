import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Not published. This module exists so both engines can be held to the same behaviour, and its only
// consumers are the two transport modules' test source sets. It carries no `mavenPublishing` block and
// is excluded from public-API tracking for that reason.

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api` throughout: everything here is part of what a subclass of the contract sees.
    api(project(":kdrant-core"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.qdrant)
    api(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
