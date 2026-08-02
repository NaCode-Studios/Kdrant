pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kdrant"

include(
    "kdrant-bom",
    "kdrant-core",
    "kdrant-transport-rest",
    "kdrant-spring-boot-starter",
    "kdrant-spring-ai",
    "kdrant-langchain4j",
    "kdrant-micrometer",
    "kdrant-otel",
    "kdrant-koog",
    "kdrant-transport-grpc",
    "kdrant-migrate",
    "kdrant-testkit",
    "example-rag",
    "example-native-image",
    "benchmarks",
)
