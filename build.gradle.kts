plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
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
    version = "2.1.0"
}

// The POM description is the sentence a catalog puts on the card, and it is the one surface a stranger
// meets before the README. It is also the one with no owner: at 2.0.0 the README, the repository
// description, the CHANGELOG and the board all moved to multiplatform and kdrant-core's POM still ended
// with "on the JVM", because nothing reads it. klibs.io was about to put that sentence next to badges
// reading iOS, macOS, Linux and Windows, generated from the same artifact's own tooling metadata.
//
// So the claim gets a check, the way every other claim in this repository does. A module that publishes
// native targets may not describe itself as JVM, and a JVM-only module may not claim otherwise.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        val descriptions = provider {
            extensions.findByType(org.gradle.api.publish.PublishingExtension::class.java)
                ?.publications
                ?.withType(org.gradle.api.publish.maven.MavenPublication::class.java)
                ?.mapNotNull { it.pom.description.orNull }
                ?.distinct()
                .orEmpty()
        }
        val publishesNative = provider {
            val kotlin = extensions.findByName("kotlin")
            kotlin is org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension &&
                kotlin.targets.any {
                    it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.native
                }
        }
        val module = path

        val verifyPublishedDescription = tasks.register("verifyPublishedDescription") {
            description = "Fails if this module's POM description misstates the platforms it publishes."
            group = "verification"
            val texts = descriptions
            val native = publishesNative
            doLast {
                val found = texts.get()
                require(found.isNotEmpty()) { "$module publishes without a POM description" }
                found.forEach { text -> checkPublishedDescription(module, text, native.get()) }
            }
        }
        tasks.named("check") { dependsOn(verifyPublishedDescription) }
    }
}

/**
 * The rule, stated once: the description names the platforms the artifact actually has, and it is
 * written in the register everything else published from here is written in.
 */
fun checkPublishedDescription(module: String, text: String, publishesNative: Boolean) {
    require(!text.contains('—')) {
        "$module's POM description carries an em dash, which is not the register anything published " +
            "from this repository is written in:\n  $text"
    }
    // Naming a native platform is the whole test. The sentence that went stale — "Core module for RAG
    // and embedding search on the JVM." — fails it by naming none, and no substring rule is needed to
    // catch it: a description that says "Runs on the JVM, iOS, macOS, Linux and Windows" is correct and
    // contains the same words.
    val nativePlatforms = listOf("iOS", "macOS", "Linux", "Windows", "Multiplatform")
    if (publishesNative) {
        require(nativePlatforms.any { text.contains(it, ignoreCase = true) }) {
            "$module publishes native targets and its POM description names none of them. That sentence " +
                "is what a catalog puts on the card, next to the iOS badge it generates from the same " +
                "artifact's own tooling metadata:\n  $text"
        }
    } else {
        // Linux is left out: a JVM artifact does run on Linux, so saying so is not a false claim.
        val claimed = listOf("iOS", "macOS", "Multiplatform").filter { text.contains(it, ignoreCase = true) }
        require(claimed.isEmpty()) {
            "$module publishes for the JVM only and its POM description claims $claimed:\n  $text"
        }
    }
}

// The runnable example, the benchmark harness and the shared test suite are not published libraries —
// exclude them from public-API tracking.
apiValidation {
    ignoredProjects.add("example-rag")
    ignoredProjects.add("example-native-image")
    ignoredProjects.add("benchmarks")
    ignoredProjects.add("kdrant-testkit")
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
        project(":kdrant-otel"),
        project(":kdrant-koog"),
        project(":kdrant-transport-grpc"),
        project(":kdrant-migrate"),
        project(":kdrant-testkit"),
        project(":example-rag"),
        project(":example-native-image"),
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
    // modules stay warning-clean across dependency and toolchain upgrades. Every compilation task
    // rather than only the JVM one: kdrant-core also compiles for JS and for nine native targets, and
    // a warning that only appears there would otherwise never fail a build.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
        }
    }

    // Detekt reads declared source directories rather than the Kotlin source sets, and its defaults
    // name src/main and src/test, which a multiplatform module does not have.
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        source.setFrom(
            files(
                "src/main/kotlin", "src/test/kotlin",
                "src/commonMain/kotlin", "src/commonTest/kotlin",
                "src/jvmMain/kotlin", "src/jvmTest/kotlin",
                "src/jsMain/kotlin", "src/nativeMain/kotlin", "src/nativeTest/kotlin",
                "src/appleMain/kotlin", "src/linuxMain/kotlin", "src/mingwMain/kotlin",
            ).filter { it.exists() },
        )
    }
}

// Aggregate the documented modules into one multi-module HTML API site (published to GitHub Pages).
// The kdrant-bom module carries no code and is intentionally excluded.
dependencies {
    dokka(project(":kdrant-core"))
    dokka(project(":kdrant-transport-rest"))
    dokka(project(":kdrant-otel"))
    dokka(project(":kdrant-migrate"))
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
    kover(project(":kdrant-otel"))
    kover(project(":kdrant-koog"))
    kover(project(":kdrant-migrate"))
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
