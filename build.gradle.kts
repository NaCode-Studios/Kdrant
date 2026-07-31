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
    // The gRPC engine's protobuf and stub classes are generated from Qdrant's own .proto files, so
    // their surface is Qdrant's to change, not ours to promise. Tracking them would bury the module's
    // real API — the transport factory — under thousands of generated lines. Everything hand-written
    // lives in dev.kdrant.transport.grpc and stays tracked.
    ignoredPackages.add("qdrant")
    ignoredPackages.add("grpc.health.v1")
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
        project(":kdrant-koog"),
        project(":kdrant-transport-grpc"),
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

    // protoc writes Kotlin into the main source set, so the checks above would otherwise run over tens
    // of thousands of generated lines: ktlint would rewrite them on the next format, and coverage would
    // be dominated by builders no test calls. Neither says anything about the code in this repository.
    // Detekt needs no such exclusion — it reads the declared source directories, not the build output.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter { exclude { it.file.path.contains("${File.separator}generated${File.separator}") } }
    }
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports {
            filters {
                excludes {
                    packages("qdrant", "grpc.health.v1")
                }
            }
        }
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
    kover(project(":kdrant-transport-grpc"))
    kover(project(":kdrant-spring-boot-starter"))
    kover(project(":kdrant-spring-ai"))
    kover(project(":kdrant-langchain4j"))
    kover(project(":kdrant-micrometer"))
    kover(project(":kdrant-koog"))
}

kover {
    reports {
        // The merged report is assembled here, so the generated-code exclusion has to be repeated here:
        // a filter set in a module applies to that module's own report, not to this one.
        filters {
            excludes {
                packages("qdrant", "grpc.health.v1")
            }
        }
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
