package dev.kdrant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
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
 * @property requestTimeout per-request timeout.
 * @property dispatcher dispatcher the client runs on; injectable for tests.
 */
public class KdrantConfig(
    public val host: String,
    public val port: Int,
    public val apiKey: String? = null,
    public val useTls: Boolean = false,
    public val requestTimeout: Duration = 30.seconds,
    public val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535, was $port" }
    }

    /** Renders the config without exposing [apiKey], so it is safe to log. */
    override fun toString(): String =
        "KdrantConfig(host=$host, port=$port, apiKey=${if (apiKey != null) "***" else "null"}, " +
            "useTls=$useTls, requestTimeout=$requestTimeout, dispatcher=$dispatcher)"
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

    /** Per-request timeout. */
    public var requestTimeout: Duration = 30.seconds

    /** Dispatcher used for client work. Injectable for tests; defaults to [Dispatchers.IO]. */
    public var dispatcher: CoroutineDispatcher = Dispatchers.IO

    internal fun build(): KdrantConfig =
        KdrantConfig(host, port, apiKey, useTls, requestTimeout, dispatcher)
}

/** Builds a [KdrantConfig] from the configuration DSL. Used by transport factories. */
public fun kdrantConfig(
    host: String,
    port: Int,
    configure: KdrantConfigBuilder.() -> Unit = {},
): KdrantConfig = KdrantConfigBuilder(host, port).apply(configure).build()
