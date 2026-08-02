import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Not published. This module exists so every engine can be held to the same behaviour, and its only
// consumers are the transport modules' test source sets. It carries no `mavenPublishing` block and
// is excluded from public-API tracking for that reason.
//
// It is multiplatform for one reason: an engine that compiles for iOS and is only ever tested from a
// JVM has been proven to link, not to work. The behaviours live in commonMain so a native test binary
// runs the same assertions the JVM does; the JUnit and Testcontainers wrapper stays on the JVM, where
// Docker is.

kotlin {
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
        // `api` throughout: everything here is part of what a consumer of the contract sees.
        commonMain.dependencies {
            api(project(":kdrant-core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlin.test)
        }
        jvmMain.dependencies {
            api(project.dependencies.platform(libs.junit.bom))
            api(libs.junit.jupiter)
            api(libs.kotlin.test.junit5)
            api(project.dependencies.platform(libs.testcontainers.bom))
            api(libs.testcontainers.qdrant)
        }
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
