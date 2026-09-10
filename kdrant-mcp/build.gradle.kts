import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Not published to Maven Central, for the same reason `kdrant-cli` is not: the artifact is the binary an
// agent spawns, and a jar in a repository is the opposite of that. The executables are attached to the
// GitHub Release by `release.yml`.
//
// The whole argument for this module is that it is native. The only MCP server for Qdrant is written in
// Python, so every agent that searches a collection does it through an interpreter, a virtual environment
// and a dependency tree. For a process an agent spawns and kills repeatedly, install footprint and cold
// start are not incidental properties; they are most of what distinguishes one server from another, and a
// JVM server would compete with the Python one on nothing.

kotlin {
    // Every target the Kotlin MCP SDK publishes for, which is every target the release attaches a CLI
    // binary for except macosX64. The SDK does not publish that one, so neither does this.
    linuxX64 { mcpBinary() }
    linuxArm64 { mcpBinary() }
    macosArm64 { mcpBinary() }
    mingwX64 { mcpBinary() }

    // The JVM target exists so the tool surface can be tested without spawning a process. It publishes
    // no binary and is not released.
    jvm()

    sourceSets {
        // The hierarchy is wired by hand, and it has to be: declaring one `dependsOn` turns the default
        // template off entirely, which left `nativeMain` attached to nothing and the binary's entry point
        // compiled into no target at all. The link failed with "could not find main", which is a long way
        // from the cause.
        //
        // posixMain holds the stdio primitives for linuxX64, linuxArm64 and macosArm64, where `size_t` and
        // `ssize_t` are the same width. mingwX64 carries its own copy, in its own leaf rather than in an
        // intermediate set, because there is one Windows target and an intermediate set with one child is
        // a level to get wrong for nothing.
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val posixMain by creating { dependsOn(nativeMain) }
        linuxX64Main.get().dependsOn(posixMain)
        linuxArm64Main.get().dependsOn(posixMain)
        macosArm64Main.get().dependsOn(posixMain)
        mingwX64Main.get().dependsOn(nativeMain)

        commonMain.dependencies {
            implementation(project(":kdrant-transport-rest"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.mcp.sdk)
            implementation(libs.kotlinx.io.core)
            // Named directly because the MCP SDK logs through it and this module has to
            // redirect that away from stdout, which is the protocol's channel.
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/** One executable per target, named `kdrant-mcp`, entered at `main`. */
fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.mcpBinary() {
    binaries {
        executable("kdrant-mcp") {
            entryPoint = "dev.kdrant.mcp.main"
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
