package dev.kdrant.internal

/**
 * Marks declarations that are shared across Kdrant modules (e.g. the transport engines)
 * but are NOT part of the stable public API and are not covered by semantic versioning.
 *
 * They are `public` only because Kotlin has no cross-module `internal`. Opt in with
 * `@OptIn(InternalKdrantApi::class)` if you really need them.
 */
@RequiresOptIn(
    message = "This is an internal Kdrant API and is not covered by semantic versioning guarantees.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
)
public annotation class InternalKdrantApi
