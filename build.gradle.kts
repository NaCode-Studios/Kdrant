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
    version = "2.2.0"
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
    // The JVM dump alone was a statement about one target. A change only Kotlin/Native can see — an
    // `expect` gaining a parameter, a declaration sliding from commonMain into jvmMain — produced no
    // diff at all, while STABILITY.md told an iOS consumer that the committed `.api` files describe
    // what they get. The klib dump is the file that makes that sentence true for them.
    //
    // It is one merged, target-annotated file per module rather than one per target, so a declaration
    // present on some targets and missing on others shows up as a diff rather than as silence. It can
    // only be regenerated on a host that builds the Apple targets: see CONTRIBUTING.md, and the macOS
    // job in ci.yml that checks it.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }

    ignoredProjects.add("example-rag")
    ignoredProjects.add("example-native-image")
    ignoredProjects.add("benchmarks")
    ignoredProjects.add("kdrant-testkit")
    // A command-line tool has users, not callers: its artifacts are binaries attached to the release
    // rather than a jar anyone compiles against, so there is no public API to promise.
    ignoredProjects.add("kdrant-cli")
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
        project(":kdrant-cli"),
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
                "src/nativeMain/kotlin",
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

// Which Qdrant this client speaks to is a fact the repository states in fourteen places: two testkit
// defaults, five integration-test image constants, four CI workflow fields, a docker-compose service,
// the version matrix, and the README beside the vendored OpenAPI document. Nothing compared them, so
// they drifted, and the drift was invisible in the direction that matters. The vendored schema's README
// said v1.18.2 while the document itself was a `master` snapshot taken before 1.19.0 shipped, which
// means the contract test — the one check standing between a Qdrant that renames a field and a client
// that keeps sending the old spelling — was validating against a document no released server matches.
//
// `info.version` cannot be the pin, which is what made the drift possible: Qdrant's own OpenAPI document
// carries "master" as its version at every released tag, v1.19.1 included. So the pin lives in
// gradle.properties, it is a released tag, and this task makes every other mention agree with it.
//
// The rule is that the newest Qdrant named anywhere is the pinned one. The older versions in the
// compatibility matrix are deliberate — they are the proof that this client still speaks to them — so
// the check is on the ceiling rather than on every value.
val qdrantPin: Provider<String> = providers.gradleProperty("qdrantVersion")

val verifyQdrantPin = tasks.register("verifyQdrantPin") {
    description = "Fails when a Qdrant version named anywhere in the repository is newer than the pin."
    group = "verification"
    val pinned = qdrantPin
    val root = layout.projectDirectory.asFile
    outputs.upToDateWhen { false }
    doLast { checkQdrantPin(pinned.get(), root) }
}
tasks.named("check") { dependsOn(verifyQdrantPin) }

/** Where a Qdrant server version can be written, and how it is spelled in each place. */
private val qdrantVersionPatterns = listOf(
    Regex("""qdrant/qdrant:v(\d+\.\d+\.\d+)"""),
    Regex("""QDRANT_VERSION:\s*"?(\d+\.\d+\.\d+)"?"""),
    Regex("""qdrant/releases/download/v(\d+\.\d+\.\d+)"""),
    Regex("""listOf\((\s*"v\d+\.\d+\.\d+",?)+\s*\)"""),
)

fun checkQdrantPin(pinned: String, root: File) {
    require(pinned.matches(Regex("""\d+\.\d+\.\d+"""))) {
        "qdrantVersion must be a released Qdrant tag without the leading v, was '$pinned'"
    }
    val ignored = setOf("build", ".git", ".gradle", ".kotlin", "node_modules")
    val found = mutableMapOf<String, MutableSet<String>>()
    root.walkTopDown()
        .onEnter { it.name !in ignored }
        .filter { it.isFile && it.extension in setOf("yml", "yaml", "kt", "kts", "md", "properties") }
        .forEach { file ->
            val text = file.readText()
            qdrantVersionPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    Regex("""\d+\.\d+\.\d+""").findAll(match.value).forEach { version ->
                        found.getOrPut(version.value) { mutableSetOf() } += file.toRelativeString(root)
                    }
                }
            }
        }
    require(found.isNotEmpty()) {
        "no Qdrant version is named anywhere, so this check is no longer checking anything"
    }
    val newest = found.keys.maxWith(qdrantVersionOrder)
    require(qdrantVersionOrder.compare(newest, pinned) <= 0) {
        "the newest Qdrant named in this repository is $newest, which is newer than the pinned " +
            "$pinned. Either move the pin in gradle.properties and run refreshVendoredQdrant, or " +
            "correct the mention:\n" + found.getValue(newest).sorted().joinToString("\n") { "  $it" }
    }
    require(newest == pinned) {
        "the pin in gradle.properties is $pinned but the newest Qdrant anything here actually runs " +
            "against is $newest. A pin nothing exercises is a claim, not a check; raise the image in " +
            "the CI matrix and the testkit defaults, or lower the pin."
    }
}

/** Numeric ordering, so 1.19.1 sorts above 1.9.9 the way a human reads it and a string sort does not. */
val qdrantVersionOrder: Comparator<String> = Comparator { left, right ->
    val a = left.split('.').map(String::toInt)
    val b = right.split('.').map(String::toInt)
    (a zip b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
}

// The vendored files are the other half of the pin, and until now nothing tied them to it. The proto
// README states the invariant plainly — "Nothing here is edited. A vendored file that has been touched
// is a file nobody can diff against upstream" — and points.proto had been edited anyway, by hand, to
// carry part of 1.19 while claiming v1.18.2. The edits happened to be faithful. Nothing would have said
// so if they had not been.
//
// So the diff the README describes becomes a task. `verifyVendoredQdrant` fetches the pinned tag and
// fails on any difference, byte for byte, over both the protobuf definitions and the OpenAPI document
// the contract test validates against. It reaches the network, so it is not wired into `check`: it runs
// as its own CI job, where a Qdrant that changed a wire format surfaces as a red build rather than as a
// request that quietly means something else.
//
// `refreshVendoredQdrant` is the other direction, and it is the only supported way to move: raise
// qdrantVersion, run it, and the files and the pin move together.
val vendoredQdrantFiles: Map<String, String> = buildMap {
    listOf(
        "collections.proto", "collections_service.proto", "points.proto", "points_service.proto",
        "snapshots_service.proto", "health_check.proto", "json_with_int.proto", "qdrant_common.proto",
    ).forEach { put("kdrant-transport-grpc/src/main/proto/$it", "lib/api/src/grpc/proto/$it") }
    put(
        "kdrant-transport-rest/src/jvmTest/resources/qdrant-openapi.json",
        "docs/redoc/master/openapi.json",
    )
}

tasks.register("verifyVendoredQdrant") {
    description = "Fails when a vendored Qdrant file differs from the pinned tag. Reaches the network."
    group = "verification"
    val pinned = qdrantPin
    val root = layout.projectDirectory.asFile
    outputs.upToDateWhen { false }
    doLast {
        val tag = "v${pinned.get()}"
        val drifted = vendoredQdrantFiles.filterNot { (local, upstream) ->
            File(root, local).readBytes().contentEquals(fetchQdrantFile(tag, upstream))
        }.keys
        require(drifted.isEmpty()) {
            "these vendored files differ from Qdrant $tag. Run refreshVendoredQdrant to take upstream's " +
                "bytes, and if a difference was deliberate it needs to stop being vendored:\n" +
                drifted.sorted().joinToString("\n") { "  $it" }
        }
        logger.lifecycle("${vendoredQdrantFiles.size} vendored files match Qdrant $tag byte for byte")
    }
}

tasks.register("refreshVendoredQdrant") {
    description = "Rewrites every vendored Qdrant file from the pinned tag. Reaches the network."
    group = "build setup"
    val pinned = qdrantPin
    val root = layout.projectDirectory.asFile
    outputs.upToDateWhen { false }
    doLast {
        val tag = "v${pinned.get()}"
        vendoredQdrantFiles.forEach { (local, upstream) ->
            File(root, local).writeBytes(fetchQdrantFile(tag, upstream))
        }
        logger.lifecycle("rewrote ${vendoredQdrantFiles.size} vendored files from Qdrant $tag")
    }
}

/** One vendored file as upstream publishes it at [tag]. A missing path is a moved file, not a 404 to swallow. */
fun fetchQdrantFile(tag: String, path: String): ByteArray {
    val url = "https://raw.githubusercontent.com/qdrant/qdrant/$tag/$path"
    return runCatching { java.net.URI(url).toURL().readBytes() }.getOrElse { cause ->
        throw GradleException("could not read $url — is $tag a released Qdrant tag, and is $path still there?", cause)
    }
}
