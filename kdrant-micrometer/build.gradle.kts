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
    api(project(":kdrant-transport-rest"))
    api(libs.ktor.client.core)
    api(libs.micrometer.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // A stub for a 56-method transport seam. Writing one by hand would be a file nobody reads and a
    // compile error every time an operation is added.
    testImplementation(libs.mockk)

    // The gRPC engine, so "one decorator, both engines" can be asserted rather than argued.
    testImplementation(project(":kdrant-transport-grpc"))
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
    coordinates("io.github.nacode-studios", "kdrant-micrometer", version.toString())
    pom {
        name.set("Kdrant Micrometer metrics")
        description.set(
            "Micrometer instrumentation for Kdrant, the coroutine-first Kotlin client for the Qdrant " +
                "vector database: request timings and outcomes per Qdrant operation, on any Micrometer " +
                "registry. JVM.",
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
