import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(platform(libs.opentelemetry.bom))
    // The API, never the SDK. A library that pulled in the SDK would decide the consumer's exporter,
    // sampler and resource for them. `opentelemetry-extension-kotlin` is what carries the current span
    // across a suspension point, which is the whole difficulty of tracing coroutines.
    api(libs.opentelemetry.api)
    api(libs.opentelemetry.extension.kotlin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    // A stub for a 50-method transport seam. Writing one by hand would be a file nobody reads and a
    // compile error every time an operation is added.
    testImplementation(libs.mockk)

    // Both engines, so the claim that one decorator covers them can be asserted rather than argued.
    testImplementation(project(":kdrant-transport-rest"))
    testImplementation(project(":kdrant-transport-grpc"))
    testImplementation(project(":kdrant-testkit"))
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
    coordinates("io.github.nacode-studios", "kdrant-otel", version.toString())
    pom {
        name.set("Kdrant OpenTelemetry tracing")
        description.set(
            "OpenTelemetry tracing for Kdrant, the coroutine-first Kotlin client for the Qdrant vector " +
                "database: one client span per operation, following the OpenTelemetry database " +
                "conventions, over any engine. Depends on the OpenTelemetry API only, so the exporter " +
                "stays yours. JVM.",
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
