plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
}

subprojects {
    group = "io.github.nacode-studios"
    version = "1.2.0"
}

// The runnable example and the benchmark harness are not published libraries — exclude them from
// public-API tracking.
apiValidation {
    ignoredProjects.add("example-rag")
    ignoredProjects.add("benchmarks")
}

// Quality tooling (format, static analysis, coverage) on the Kotlin source modules — the code-less
// kdrant-bom is excluded.
configure(
    listOf(
        project(":kdrant-core"),
        project(":kdrant-transport-rest"),
        project(":kdrant-spring-boot-starter"),
        project(":kdrant-spring-ai"),
        project(":kdrant-langchain4j"),
        project(":kdrant-micrometer"),
        project(":example-rag"),
    ),
) {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
    }

    // Treat every Kotlin compiler warning — deprecations included — as a build error, so these
    // modules stay warning-clean across dependency and toolchain upgrades.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
        }
    }
}

// Aggregate the documented modules into one multi-module HTML API site (published to GitHub Pages).
// The kdrant-bom module carries no code and is intentionally excluded.
dependencies {
    dokka(project(":kdrant-core"))
    dokka(project(":kdrant-transport-rest"))
}

// Coverage over the published library modules. The example and the benchmark harness are excluded:
// neither ships, and counting them would move the number without saying anything about the library.
dependencies {
    kover(project(":kdrant-core"))
    kover(project(":kdrant-transport-rest"))
    kover(project(":kdrant-spring-boot-starter"))
    kover(project(":kdrant-spring-ai"))
    kover(project(":kdrant-langchain4j"))
    kover(project(":kdrant-micrometer"))
}

kover {
    reports {
        // The integration tests need Docker and are skipped without it, so the floor is set for the
        // unit-test-only run every contributor and every CI job does. It is a floor, not a target:
        // it exists to catch a module arriving untested, not to be inched towards.
        verify {
            rule {
                minBound(75)
            }
        }
    }
}
