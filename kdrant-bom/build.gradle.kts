plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

// group and version are inherited from the root `subprojects { }` block.

dependencies {
    constraints {
        api("io.github.nacode-studios:kdrant-core:${project.version}")
        api("io.github.nacode-studios:kdrant-transport-rest:${project.version}")
        api("io.github.nacode-studios:kdrant-spring-boot-starter:${project.version}")
        api("io.github.nacode-studios:kdrant-spring-ai:${project.version}")
        api("io.github.nacode-studios:kdrant-langchain4j:${project.version}")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.nacode-studios", "kdrant-bom", version.toString())
    pom {
        name.set("Kdrant BOM")
        description.set(
            "Bill of Materials for Kdrant — import it to keep kdrant-core and kdrant-transport-rest " +
                "on a single, aligned version.",
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
