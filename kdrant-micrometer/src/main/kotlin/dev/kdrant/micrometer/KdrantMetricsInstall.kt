package dev.kdrant.micrometer

import io.ktor.client.HttpClientConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag

/**
 * Install [KdrantMetrics] on a Kdrant client, through the `configureClient` seam.
 *
 * Replaced by the `decorateTransport` form, which measures the operation instead of the HTTP request
 * and therefore also covers a client built with `KdrantGrpc`:
 *
 * ```kotlin
 * val qdrant = Kdrant(host = "localhost", decorateTransport = kdrantMetrics(registry))
 * ```
 *
 * The tags change with it. See [kdrantMetrics] for what the replacement publishes and why the
 * `operation` tag stops being a route template.
 *
 * @param registry the Micrometer registry the meters go to.
 * @param prefix meter name prefix; the timer is `<prefix>.requests`.
 * @param tags tags added to every meter, e.g. the cluster or environment this client talks to.
 */
@Deprecated(
    "Metrics moved to the transport seam, where both engines report. Pass " +
        "decorateTransport = kdrantMetrics(registry) to the factory instead.",
    ReplaceWith("kdrantMetrics(registry, prefix, tags)", "dev.kdrant.micrometer.kdrantMetrics"),
)
public fun HttpClientConfig<*>.kdrantMetrics(
    registry: MeterRegistry,
    prefix: String = "kdrant",
    tags: Iterable<Tag> = emptyList(),
) {
    @Suppress("DEPRECATION")
    install(KdrantMetrics) {
        this.registry = registry
        this.prefix = prefix
        this.tags = tags
    }
}
