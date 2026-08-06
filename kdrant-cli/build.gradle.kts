import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Not published to Maven Central. The whole argument for this module is a binary with no JVM, no
// classpath and no install step, and a jar in a repository is the opposite of that: the artifacts are
// the executables, attached to the GitHub Release by `release.yml`.
//
// It exists because M38 made it possible. `kdrant-transport-rest` and `kdrant-migrate` compile for
// linuxX64, macosArm64 and mingwX64, so a static tool is a build file rather than a project — and it is
// the strongest demonstration this repository can make that the multiplatform claim means something: a
// tool nobody could have built from Kdrant a release ago.

kotlin {
    // Three host platforms, and only three. An iOS binary would be a command line nobody can type, and
    // a JVM one would have given up the only advantage the tool has over calling the API directly.
    linuxX64 { cliBinary() }
    linuxArm64 { cliBinary() }
    macosArm64 { cliBinary() }
    macosX64 { cliBinary() }
    mingwX64 { cliBinary() }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kdrant-transport-rest"))
            implementation(project(":kdrant-migrate"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/**
 * One executable per target, named `kdrant`, with `main` as its entry point.
 *
 * `debuggable = false` on the release binary is what makes it small enough to be worth downloading; the
 * debug binary stays for a stack trace worth reading while developing.
 */
fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.cliBinary() {
    binaries {
        executable("kdrant") {
            entryPoint = "dev.kdrant.cli.main"
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
