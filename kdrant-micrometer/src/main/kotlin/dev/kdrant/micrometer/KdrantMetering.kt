package dev.kdrant.micrometer

import dev.kdrant.transport.QdrantTransport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer

/**
 * Micrometer metrics for Kdrant, on the seam every engine goes through.
 *
 * ```kotlin
 * val qdrant = Kdrant(host = "localhost", decorateTransport = kdrantMetrics(registry))
 * ```
 *
 * The same call works on `KdrantGrpc` and produces the same timer with the same tags. That is the
 * point of measuring here rather than inside a Ktor client: the operation is what the caller asked for,
 * and it is the same operation whichever protocol carried it.
 *
 * ### What is published
 *
 * | Meter | What it is |
 * | --- | --- |
 * | `<prefix>.requests` | a [Timer] tagged `operation` and `outcome` |
 *
 * `operation` is the client operation — `query`, `upsert`, `create_collection` — the same vocabulary
 * `kdrant-otel` puts on `db.operation.name`, so a metric and a span can be read side by side.
 *
 * `outcome` is `SUCCESS`, `CANCELLED`, or the [dev.kdrant.KdrantException] subclass that ended the
 * call: `Timeout`, `RateLimited`, `CollectionNotFound`, and so on. The hierarchy is sealed, so that
 * tag's cardinality is bounded by the client rather than by the server's error text.
 *
 * ### What is not published
 *
 * No collection name. It is caller-chosen and unbounded, and a deployment with a collection per tenant
 * would turn one timer into a time series per tenant, which is how a metrics bill becomes a surprise.
 * The collection is on the span instead, where the cost of a high-cardinality attribute is paid per
 * trace rather than per series forever.
 *
 * No HTTP status and no method either, both of which the Ktor plugin published: neither exists on a
 * gRPC call, and a tag that is only present on one engine is a tag no dashboard can rely on.
 *
 * @param registry the registry the meters go to.
 * @param prefix meter name prefix; the timer is `<prefix>.requests`.
 * @param tags tags added to every meter, e.g. the cluster or environment this client talks to.
 */
public fun kdrantMetrics(
    registry: MeterRegistry,
    prefix: String = "kdrant",
    tags: Iterable<Tag> = emptyList(),
): (QdrantTransport) -> QdrantTransport = { transport ->
    transport.metered(registry, prefix, tags)
}

/**
 * Wraps this transport so every operation is timed. See [kdrantMetrics], which is the form to pass to
 * a transport factory.
 */
public fun QdrantTransport.metered(
    registry: MeterRegistry,
    prefix: String = "kdrant",
    tags: Iterable<Tag> = emptyList(),
): QdrantTransport = MeteredQdrantTransport(this, registry, prefix, tags)
