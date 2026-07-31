import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    // No dokka-javadoc here: its generator refuses a multiplatform project outright. Maven Central
    // requires a -javadoc.jar to exist rather than to be Javadoc, so the publishing plugin fills it
    // with Dokka's HTML output, which is what a Kotlin reader wants anyway.
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    // The JVM stays the reference target: it is what every engine and adapter in this repository
    // compiles against, and `kdrant-core-jvm` is the artifact those modules resolve.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Everything the two dependencies support and someone might plausibly run a Qdrant client on.
    // watchOS and tvOS are left out: they cost one line each and neither has a use case that would
    // justify carrying the klibs, so they wait for someone to ask.
    //
    // Kotlin/JS is left out for a stronger reason. There is no JS engine — Ktor CIO and grpc-java are
    // both JVM-only — so kdrant-core on JS would be models and a DSL with nothing to send them over.
    // The target is not free: its Karma and webpack test tooling is the only npm dependency graph this
    // repository has, and it arrived carrying a high-severity advisory in a package no published
    // artifact contains. One line brings it back the day someone writes a JS engine.
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
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.property)
        }
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.nacode-studios", "kdrant-core", version.toString())
    pom {
        name.set("Kdrant Core")
        description.set(
            "Idiomatic, coroutine-first Kotlin client for the Qdrant vector database — suspend " +
                "functions, a type-safe filter/query DSL, kotlinx-serialization models, and a pluggable " +
                "transport seam. Core module for RAG and embedding search on the JVM.",
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
