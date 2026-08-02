package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfigBuilder
import dev.kdrant.QdrantClient
import dev.kdrant.kdrantConfig
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import kotlin.time.Duration

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
 * @param maxConnectionsPerRoute how many connections the pool keeps open to the Qdrant host; `null`
 *   (default) keeps the engine's. These are engine settings, not connection settings, which is why
 *   they live here rather than on [dev.kdrant.KdrantConfig] — that stays transport-neutral. Applied
 *   by the JVM engine; the native engines do not expose a pool and ignore them.
 * @param keepAliveTime how long an idle pooled connection is kept; `null` (default) keeps the engine's.
 *   Applied by the JVM engine, ignored by the native ones.
 * @param requestId called once per request for the value of an `X-Request-Id` header, so a call can be
 *   followed into Qdrant's logs. `null` (default) sends no header. Return your own trace id to tie the
 *   two together, or `{ UUID.randomUUID().toString() }` for a standalone one.
 * @param configureClient an escape hatch applied last to the underlying Ktor [HttpClientConfig] — install
 *   your own plugins (metrics, tracing) or override any default Kdrant set. Runs after Kdrant's own
 *   setup, so it wins.
 */
public fun Kdrant(
    host: String,
    port: Int = 6333,
    upsertBatchSize: Int = 1000,
    maxUpsertBytes: Int = DEFAULT_MAX_UPSERT_BYTES,
    logLevel: LogLevel? = null,
    maxConnectionsPerRoute: Int? = null,
    keepAliveTime: Duration? = null,
    requestId: (() -> String)? = null,
    configureClient: (HttpClientConfig<*>.() -> Unit)? = null,
    configure: KdrantConfigBuilder.() -> Unit = {},
): QdrantClient =
    QdrantClient(
        RestQdrantTransport(
            kdrantConfig(host, port, configure),
            upsertBatchSize = upsertBatchSize,
            maxUpsertBytes = maxUpsertBytes,
            logLevel = logLevel,
            maxConnectionsPerRoute = maxConnectionsPerRoute,
            keepAliveTime = keepAliveTime,
            requestId = requestId,
            configureClient = configureClient,
        ),
    )
