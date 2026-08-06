plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    jmhImplementation(project(":kdrant-transport-rest"))
    jmhImplementation(libs.kotlinx.coroutines.core)

    // The competitor's artifact, so the comparison is run rather than argued. It is a benchmark
    // dependency and only that: this module publishes nothing, so it cannot reach a consumer's
    // classpath, and the check below fails the build if it ever appears anywhere else.
    jmhImplementation(libs.qdrant.client)
    jmhImplementation(libs.grpc.okhttp)
    // protobuf-java and Guava come in transitively but the Kotlin compiler needs them on the compile
    // classpath to see through the official client's generated message hierarchy.
    jmhImplementation(libs.protobuf.java)
    jmhImplementation(libs.guava)
}

// A dependency added for a measurement must not become a dependency of the library. The benchmark
// module is not published, which is the real guarantee; this is the check that says so out loud, on
// every build, so nobody has to remember why `io.qdrant:client` is in the version catalog.
val verifyCompetitorStaysInBenchmarks by tasks.registering {
    description = "Fails if the official Qdrant client reaches a configuration outside the benchmarks."
    group = "verification"
    doLast {
        require(!project.plugins.hasPlugin("com.vanniktech.maven.publish")) {
            "benchmarks publishes now, and it depends on io.qdrant:client. One of those has to change."
        }
    }
}
tasks.named("check") { dependsOn(verifyCompetitorStaysInBenchmarks) }

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
}
