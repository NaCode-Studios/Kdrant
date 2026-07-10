package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfigBuilder
import dev.kdrant.QdrantClient
import dev.kdrant.kdrantConfig

/**
 * Entry point: creates a [QdrantClient] backed by the REST/Ktor engine.
 *
 * ```kotlin
 * Kdrant(host = "localhost", port = 6333) {
 *     apiKey = "test-key"
 *     requestTimeout = 5.seconds
 * }.use { qdrant ->
 *     qdrant.createCollection("docs") { vectors { size = 768; distance = Distance.COSINE } }
 * }
 * ```
 *
 * @param port defaults to 6333 (Qdrant's REST port; gRPC's 6334 is not used by this engine).
 * @param upsertBatchSize maximum points per upsert request; larger batches are split automatically.
 */
public fun Kdrant(
    host: String,
    port: Int = 6333,
    upsertBatchSize: Int = 1000,
    configure: KdrantConfigBuilder.() -> Unit = {},
): QdrantClient =
    QdrantClient(RestQdrantTransport(kdrantConfig(host, port, configure), upsertBatchSize = upsertBatchSize))
