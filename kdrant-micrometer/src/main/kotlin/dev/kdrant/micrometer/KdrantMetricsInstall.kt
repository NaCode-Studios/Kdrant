package dev.kdrant.micrometer

import io.ktor.client.HttpClientConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag

/**
 * Install [KdrantMetrics] on a Kdrant client, through the `configureClient` seam:
 *
 * ```kotlin
 * Kdrant(host = "localhost", configureClient = { kdrantMetrics(registry) }).use { qdrant ->
 *     // every request is now timed as kdrant.requests
 * }
 * ```
 *
 * @param registry the Micrometer registry the meters go to.
 * @param prefix meter name prefix; the timer is `<prefix>.requests`.
 * @param tags tags added to every meter, e.g. the cluster or environment this client talks to.
 */
public fun HttpClientConfig<*>.kdrantMetrics(
    registry: MeterRegistry,
    prefix: String = "kdrant",
    tags: Iterable<Tag> = emptyList(),
) {
    install(KdrantMetrics) {
        this.registry = registry
        this.prefix = prefix
        this.tags = tags
    }
}
