package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfigBuilder
import dev.kdrant.QdrantClient
import dev.kdrant.kdrantConfig
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel

/**
 * Entry point: creates a [QdrantClient] backed by the REST/Ktor engine.
 *
 * ```kotlin
 * Kdrant(host = "localhost", port = 6333) {
 *     apiKey = "test-key"
 *     requestTimeout = 5.seconds
 * }.use { qdrant ->
 *     qdrant.createCollection("docs") { vector { size = 768; distance = Distance.COSINE } }
 * }
 * ```
 *
 * @param port defaults to 6333 (Qdrant's REST port; gRPC's 6334 is not used by this engine).
 * @param upsertBatchSize maximum points per upsert request; larger batches are split automatically.
 * @param maxUpsertBytes soft cap on an upsert batch's serialized size, so the ~32 MiB REST payload limit is
 *   respected even for high-dimensional vectors. A batch is also split when it reaches [upsertBatchSize]
 *   points, whichever comes first.
 * @param logLevel when non-null, installs request/response logging at this level with the `api-key`
 *   header redacted so the key never reaches the logs. `null` (default) disables logging.
 * @param configureClient an escape hatch applied last to the underlying Ktor [HttpClientConfig] — install
 *   your own plugins (metrics, OpenTelemetry), tune the CIO engine (`engine { … }`), or override any
 *   default. Runs after Kdrant's own setup, so it can override it.
 */
public fun Kdrant(
    host: String,
    port: Int = 6333,
    upsertBatchSize: Int = 1000,
    maxUpsertBytes: Int = DEFAULT_MAX_UPSERT_BYTES,
    logLevel: LogLevel? = null,
    configureClient: (HttpClientConfig<*>.() -> Unit)? = null,
    configure: KdrantConfigBuilder.() -> Unit = {},
): QdrantClient =
    QdrantClient(
        RestQdrantTransport(
            kdrantConfig(host, port, configure),
            upsertBatchSize = upsertBatchSize,
            maxUpsertBytes = maxUpsertBytes,
            logLevel = logLevel,
            configureClient = configureClient,
        ),
    )
