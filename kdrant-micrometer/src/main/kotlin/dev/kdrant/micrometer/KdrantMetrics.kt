package dev.kdrant.micrometer

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer

/** Settings for the [KdrantMetrics] plugin. */
public class KdrantMetricsConfig {

    /** Registry the meters are published to. Required. */
    public var registry: MeterRegistry? = null

    /** Meter name prefix; the timer is `<prefix>.requests`. */
    public var prefix: String = "kdrant"

    /** Tags added to every meter, e.g. the cluster or environment this client talks to. */
    public var tags: Iterable<Tag> = emptyList()
}

/**
 * Ktor client plugin recording one timer per Qdrant request.
 *
 * Prefer [kdrantMetrics], which installs it. Published meters:
 *
 * - `kdrant.requests` — a [Timer] tagged `operation`, `method`, `status` and `outcome`.
 *
 * `operation` is the route template, not the URL: collection, field and snapshot names are replaced
 * with placeholders, so a deployment with thousands of collections does not turn into thousands of
 * time series. `outcome` is `SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR` or `FAILURE`, the last one for a
 * request that never produced a response.
 */
public val KdrantMetrics: ClientPlugin<KdrantMetricsConfig> =
    createClientPlugin("KdrantMetrics", ::KdrantMetricsConfig) {
        val registry = requireNotNull(pluginConfig.registry) {
            "KdrantMetrics needs a MeterRegistry; set registry = ... when installing it"
        }
        val timerName = "${pluginConfig.prefix}.requests"
        val commonTags = pluginConfig.tags.toList()

        on(Send) { request ->
            val started = Timer.start(registry)
            val method = request.method.value
            val operation = operationOf(request.url.encodedPathSegments)
            try {
                val call = proceed(request)
                val status = call.response.status.value
                started.stop(
                    Timer.builder(timerName)
                        .tags(commonTags)
                        .tag("operation", operation)
                        .tag("method", method)
                        .tag("status", status.toString())
                        .tag("outcome", outcomeOf(status))
                        .register(registry),
                )
                call
            } catch (failure: Throwable) {
                started.stop(
                    Timer.builder(timerName)
                        .tags(commonTags)
                        .tag("operation", operation)
                        .tag("method", method)
                        .tag("status", "none")
                        .tag("outcome", "FAILURE")
                        .register(registry),
                )
                throw failure
            }
        }
    }

/**
 * Reduce a request path to the route it belongs to. Every segment that carries a caller-chosen name
 * becomes a placeholder, which is what keeps the tag's cardinality bounded.
 */
internal fun operationOf(pathSegments: List<String>): String {
    val segments = pathSegments.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "/"
    val rewritten = buildList {
        var index = 0
        while (index < segments.size) {
            val segment = segments[index]
            add(segment)
            // The segment after any of these is a name the caller chose, never a fixed route part.
            // `/collections/aliases` is the exception: there `aliases` is the route, not a collection.
            val placeholder = when {
                segment == "collections" && segments.getOrNull(index + 1) != "aliases" -> "{collection}"
                segment == "snapshots" && index + 1 < segments.size -> "{snapshot}"
                segment == "index" && index + 1 < segments.size -> "{field}"
                else -> null
            }
            if (placeholder != null && index + 1 < segments.size) {
                add(placeholder)
                index++
            }
            index++
        }
    }
    return "/" + rewritten.joinToString("/")
}

private fun outcomeOf(status: Int): String = when (status) {
    in 200..399 -> "SUCCESS"
    in 400..499 -> "CLIENT_ERROR"
    else -> "SERVER_ERROR"
}
