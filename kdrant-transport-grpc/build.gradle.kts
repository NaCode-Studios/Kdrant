import com.google.protobuf.gradle.id
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":kdrant-core"))

    // grpc-kotlin generates suspend functions and Flows, which is why the stubs are generated here
    // rather than taken from io.qdrant:client: adapting ListenableFuture back into coroutines is the
    // shape this library exists to avoid.
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.kotlin)
    // OkHttp rather than netty-shaded. The shaded Netty jar is roughly 9 MB on its own, most of what
    // the README's footprint table charges to the official client.
    implementation(libs.grpc.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.grpc.inprocess)

    // The shared client contract, run here against the same real Qdrant the REST engine is held to.
    testImplementation(project(":kdrant-testkit"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.qdrant)
}

// Resolved before the protobuf { } block: inside it the extension receiver shadows the catalog accessor.
val protocArtifact = libs.protobuf.protoc.get().toString()
val grpcJavaPlugin = libs.protoc.gen.grpc.java.get().toString()
val grpcKotlinPlugin = "${libs.protoc.gen.grpc.kotlin.get()}:jdk8@jar"

protobuf {
    protoc { artifact = protocArtifact }
    plugins {
        id("grpc") { artifact = grpcJavaPlugin }
        id("grpckt") { artifact = grpcKotlinPlugin }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.nacode-studios", "kdrant-transport-grpc", version.toString())
    pom {
        name.set("Kdrant gRPC transport")
        description.set(
            "The opt-in gRPC engine for Kdrant, the coroutine-first Kotlin client for the Qdrant vector " +
                "database. REST remains the default; reach for this when throughput or streaming is the " +
                "bottleneck. JVM only, because it is built on grpc-java.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/NaCode-Studios/Kdrant")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("NaCode-Studios")
                name.set("NaCode Studios")
                url.set("https://github.com/NaCode-Studios")
            }
        }
        scm {
            url.set("https://github.com/NaCode-Studios/Kdrant")
            connection.set("scm:git:https://github.com/NaCode-Studios/Kdrant.git")
            developerConnection.set("scm:git:ssh://git@github.com/NaCode-Studios/Kdrant.git")
        }
    }
}
