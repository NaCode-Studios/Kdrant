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
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.assertj.core)
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
    coordinates("io.github.nacode-studios", "kdrant-spring-boot-starter", version.toString())
    pom {
        name.set("Kdrant Spring Boot starter")
        description.set(
            "Spring Boot auto-configuration for Kdrant, the coroutine-first Kotlin client for the Qdrant " +
                "vector database. Adds `kdrant.*` configuration properties and exposes a ready-to-inject " +
                "QdrantClient bean.",
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
