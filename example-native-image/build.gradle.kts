plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.graalvm.native)
}

// The README's comparison table said Kdrant was GraalVM native-image friendly and needed no reflection
// configuration, and nobody had ever built an image. This module is the claim being measured: a small
// application that opens a client, creates a collection, upserts, searches and cleans up, compiled with
// native-image in CI and run against a real Qdrant. Either it works with no configuration, which makes
// the table true and turns the claim into a job that fails the day a dependency starts reflecting, or
// it needs metadata, in which case Kdrant ships it and the table says so.
//
// Not published, and not in the coverage aggregate: it is a probe, not a library.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kdrant-transport-rest"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.kdrant.example.nativeimage.SmokeKt")
}

graalvmNative {
    // The GraalVM JDK comes from the runner rather than from a toolchain lookup: setup-graalvm has
    // already put it on JAVA_HOME, and letting Gradle go looking finds the wrong one.
    toolchainDetection.set(false)

    binaries.named("main") {
        imageName.set("kdrant-native-smoke")
        mainClass.set("dev.kdrant.example.nativeimage.SmokeKt")

        // --no-fallback is the whole point. Without it, native-image quietly produces an image that
        // ships a JVM and starts in a second, and the cold-start number in the README would be a
        // measurement of nothing. With it, a missing piece of reachability metadata fails the build.
        buildArgs.add("--no-fallback")
        buildArgs.add("-H:+ReportExceptionStackTraces")

        // GraalVM for JDK 21 initializes everything at run time unless told otherwise, and these four
        // enums are read while the analysis reads annotations, which is build time. They are a
        // Kotlin/GraalVM interaction rather than anything about Kdrant, and they are class
        // initialization rather than reachability metadata: no reflection, no resource and no proxy
        // configuration is committed here, which is the claim the README's comparison table makes.
        buildArgs.add(
            "--initialize-at-build-time=" +
                "kotlin.DeprecationLevel," +
                "kotlin.annotation.AnnotationRetention," +
                "kotlin.annotation.AnnotationTarget," +
                "kotlin.coroutines.intrinsics.CoroutineSingletons",
        )
    }
}
