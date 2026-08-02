package dev.kdrant.otel

import dev.kdrant.transport.QdrantTransport
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey

/**
 * Tracing for Kdrant, on the seam every engine goes through.
 *
 * `kdrant-micrometer` answers how slow Qdrant is. It cannot answer where a slow request sat, and in a
 * RAG service that is usually the question worth asking: the call took 900 ms and the search was 40 of
 * them, or the search was 700 and the embedding was 40. A metric cannot separate those. A span can,
 * because it nests inside the caller's.
 *
 * ```kotlin
 * val qdrant = Kdrant(
 *     host = "localhost",
 *     decorateTransport = kdrantTracing(openTelemetry, serverAddress = "localhost", serverPort = 6333),
 * )
 * ```
 *
 * The same call works on `KdrantGrpc`, and produces the same spans: the decorator sits above the wire,
 * so one implementation covers both engines and a third would inherit it.
 *
 * ### What the spans say
 *
 * Attribute names follow OpenTelemetry's database conventions rather than an invented vocabulary, so
 * the spans group with everything else in the trace:
 *
 * | Attribute | Value |
 * | --- | --- |
 * | `db.system.name` | `qdrant` |
 * | `db.operation.name` | the client operation, e.g. `query`, `upsert`, `create_collection` |
 * | `db.collection.name` | the collection, when the operation names one |
 * | `server.address`, `server.port` | when you pass them |
 * | `error.type` | the [dev.kdrant.KdrantException] subclass, on a failed span |
 *
 * The span name is `<operation> <collection>`, or just the operation for the calls that are not about
 * one collection.
 *
 * ### What the spans never say
 *
 * No payload value, no vector, no filter. A span attribute is exported to a backend many people can
 * read, and the whole point of a filter is often that it names a tenant. That is also why a failed span
 * carries `error.type` and no server message: Qdrant's errors quote the request back, and the request
 * is the thing being kept out.
 *
 * The attribute set is closed and asserted in the tests, so a future operation cannot quietly widen it.
 */
public fun kdrantTracing(
    openTelemetry: OpenTelemetry,
    serverAddress: String? = null,
    serverPort: Int? = null,
): (QdrantTransport) -> QdrantTransport = { transport ->
    transport.traced(openTelemetry, serverAddress, serverPort)
}

/**
 * Wraps this transport so every operation opens a client span. See [kdrantTracing], which is the form
 * to pass to a transport factory.
 */
public fun QdrantTransport.traced(
    openTelemetry: OpenTelemetry,
    serverAddress: String? = null,
    serverPort: Int? = null,
): QdrantTransport = TracingQdrantTransport(this, openTelemetry, serverAddress, serverPort)

/** The name Kdrant registers its instrumentation under, so a backend can attribute the spans. */
internal const val INSTRUMENTATION_NAME: String = "dev.kdrant"

internal object KdrantAttributes {
    /**
     * Declared here rather than taken from `opentelemetry-semconv`, which is published as incubating
     * and renames keys between releases. The strings are the convention's; the dependency is not.
     */
    val DB_SYSTEM_NAME: AttributeKey<String> = AttributeKey.stringKey("db.system.name")
    val DB_OPERATION_NAME: AttributeKey<String> = AttributeKey.stringKey("db.operation.name")
    val DB_COLLECTION_NAME: AttributeKey<String> = AttributeKey.stringKey("db.collection.name")
    val SERVER_ADDRESS: AttributeKey<String> = AttributeKey.stringKey("server.address")
    val SERVER_PORT: AttributeKey<Long> = AttributeKey.longKey("server.port")
    val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")

    const val QDRANT: String = "qdrant"
}
