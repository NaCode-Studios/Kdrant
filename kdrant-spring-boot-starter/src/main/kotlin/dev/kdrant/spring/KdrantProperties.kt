package dev.kdrant.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the Kdrant client, bound from `kdrant.*` application properties.
 *
 * ```properties
 * kdrant.host=localhost
 * kdrant.port=6333
 * kdrant.api-key=${QDRANT_API_KEY:}
 * kdrant.use-tls=true
 * kdrant.request-timeout=10s
 * ```
 */
@ConfigurationProperties(prefix = "kdrant")
public class KdrantProperties {

    /** Qdrant host. */
    public var host: String = "localhost"

    /** Qdrant REST port. */
    public var port: Int = 6333

    /** API key sent as the `api-key` header; `null`/blank disables auth. */
    public var apiKey: String? = null

    /** Use HTTPS instead of HTTP. Required when [apiKey] is set. */
    public var useTls: Boolean = false

    /** Per-request timeout. */
    public var requestTimeout: Duration = Duration.ofSeconds(30)

    /** How many times to retry a transient failure (`0` disables retries). */
    public var maxRetries: Int = 3

    /** Maximum points per upsert request; larger batches are split automatically. */
    public var upsertBatchSize: Int = 1000
}
