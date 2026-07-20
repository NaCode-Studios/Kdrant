plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

subprojects {
    group = "io.github.nacode-studios"
    version = "0.1.0"
}

// Aggregate the documented modules into one multi-module HTML API site (published to GitHub Pages).
// The kdrant-bom module carries no code and is intentionally excluded.
dependencies {
    dokka(project(":kdrant-core"))
    dokka(project(":kdrant-transport-rest"))
}
