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
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
}
