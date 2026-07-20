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
    "example-rag",
)
