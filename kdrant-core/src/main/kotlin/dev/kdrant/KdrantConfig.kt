package dev.kdrant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** DSL marker isolating Kdrant builder scopes (config, points, filters, ...). */
@DslMarker
public annotation class KdrantDsl

/**
 * Resolved, immutable connection configuration for a Kdrant client.
 *
 * @property host Qdrant host.
 * @property port Qdrant port (1..65535).
 * @property apiKey API key sent as the `api-key` header; `null` disables auth.
 * @property useTls use HTTPS instead of HTTP.
 * @property requestTimeout per-request timeout (applies to each attempt).
 * @property maxRetries how many times to retry a retryable failure (HTTP 429/502/503/504 and
 *   transient I/O errors) with exponential backoff + jitter. `0` disables retries.
 * @property retryBaseDelay base delay before the first retry; each subsequent retry backs off
 *   exponentially, capped at [retryMaxDelay]. The server's `Retry-After` is honored when present.
 * @property retryMaxDelay upper bound on a single retry delay.
 * @property dispatcher dispatcher the client runs on; injectable for tests.
 */
public class KdrantConfig(
    public val host: String,
    public val port: Int,
    public val apiKey: String? = null,
    public val useTls: Boolean = false,
    public val requestTimeout: Duration = 30.seconds,
    public val maxRetries: Int = 3,
    public val retryBaseDelay: Duration = 500.milliseconds,
    public val retryMaxDelay: Duration = 5.seconds,
    public val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535, was $port" }
        require(apiKey == null || useTls) {
            "useTls must be true when an apiKey is set, otherwise the key is sent over plaintext HTTP. " +
                "Set useTls = true, or drop the apiKey for a local, unauthenticated node."
        }
        require(maxRetries >= 0) { "maxRetries must be >= 0, was $maxRetries" }
        require(retryBaseDelay.isPositive()) { "retryBaseDelay must be positive, was $retryBaseDelay" }
        require(retryMaxDelay >= retryBaseDelay) {
            "retryMaxDelay ($retryMaxDelay) must be >= retryBaseDelay ($retryBaseDelay)"
        }
    }

    /** Renders the config without exposing [apiKey], so it is safe to log. */
    override fun toString(): String =
        "KdrantConfig(host=$host, port=$port, apiKey=${if (apiKey != null) "***" else "null"}, " +
            "useTls=$useTls, requestTimeout=$requestTimeout, maxRetries=$maxRetries, " +
            "retryBaseDelay=$retryBaseDelay, retryMaxDelay=$retryMaxDelay, dispatcher=$dispatcher)"
}

/** Mutable builder backing the `Kdrant(host, port) { ... }` configuration DSL. */
@KdrantDsl
public class KdrantConfigBuilder internal constructor(
    private val host: String,
    private val port: Int,
) {
    /** API key sent as the `api-key` header. `null` disables auth. */
    public var apiKey: String? = null

    /** Use HTTPS instead of HTTP. Required in production when sending an [apiKey]. */
    public var useTls: Boolean = false

    /** Per-request timeout (applies to each attempt). */
    public var requestTimeout: Duration = 30.seconds

    /** How many times to retry a retryable failure (HTTP 429/502/503/504, transient I/O). `0` disables. */
    public var maxRetries: Int = 3

    /** Base delay before the first retry; subsequent retries back off exponentially up to [retryMaxDelay]. */
    public var retryBaseDelay: Duration = 500.milliseconds

    /** Upper bound on a single retry delay. */
    public var retryMaxDelay: Duration = 5.seconds

    /** Dispatcher used for client work. Injectable for tests; defaults to [Dispatchers.IO]. */
    public var dispatcher: CoroutineDispatcher = Dispatchers.IO

    internal fun build(): KdrantConfig =
        KdrantConfig(
            host, port, apiKey, useTls, requestTimeout,
            maxRetries, retryBaseDelay, retryMaxDelay, dispatcher,
        )
}

/** Builds a [KdrantConfig] from the configuration DSL. Used by transport factories. */
public fun kdrantConfig(
    host: String,
    port: Int,
    configure: KdrantConfigBuilder.() -> Unit = {},
): KdrantConfig = KdrantConfigBuilder(host, port).apply(configure).build()
