package dev.kdrant.transport.grpc

import dev.kdrant.KdrantConfigBuilder
import dev.kdrant.QdrantClient
import dev.kdrant.kdrantConfig

/**
 * Entry point: creates a [QdrantClient] backed by the gRPC engine.
 *
 * ```kotlin
 * KdrantGrpc(host = "localhost") { requestTimeout = 5.seconds }.use { qdrant ->
 *     qdrant.upsert("docs", wait = true) { point(1) { vector(0.1f, 0.2f) } }
 * }
 * ```
 *
 * REST stays the recommended engine. Reach for this one when throughput is the bottleneck: HTTP/2
 * multiplexing and protobuf framing win on large upserts and on many concurrent small reads. What it
 * costs is the eleven operations Qdrant serves over HTTP only — telemetry, metrics, issues, snapshot
 * transfer and shard-scope snapshots — which throw here rather than pretending; see
 * [GrpcQdrantTransport].
 *
 * @param port defaults to **6334**, Qdrant's gRPC port. It is not 6333: a config carried over from the
 *   REST engine will point at the wrong listener, and nothing rewrites it silently.
 * @param upsertBatchSize maximum points per upsert request; larger lists and flows are split. gRPC's
 *   default message limit is 4 MiB, well under REST's 32 MiB, so this default is lower than the REST
 *   engine's for the same reason that one exists.
 */
public fun KdrantGrpc(
    host: String,
    port: Int = 6334,
    upsertBatchSize: Int = 256,
    configure: KdrantConfigBuilder.() -> Unit = {},
): QdrantClient {
    val config = kdrantConfig(host, port, configure)
    return QdrantClient(GrpcQdrantTransport(config, managedChannel(config), upsertBatchSize))
}
