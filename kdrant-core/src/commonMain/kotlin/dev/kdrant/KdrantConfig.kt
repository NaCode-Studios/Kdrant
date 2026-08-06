package dev.kdrant

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** DSL marker isolating Kdrant builder scopes (config, points, filters, ...). */
@DslMarker
public annotation class KdrantDsl

/**
 * Resolved, immutable connection configuration for a Kdrant client.
 *
 * ### Credentials
 *
 * [apiKey] is the cluster's master key: full read and write over every collection. [bearerToken] is a
 * Qdrant JWT, which carries claims that narrow it — read-only, a named collection, or a payload filter
 * deciding which points the caller may see at all. Exactly one of the two, or neither.
 *
 * A credential is refused over plaintext HTTP unless [host] is a loopback address, because a key sent
 * in the clear across a network is a key someone else has. A request to `127.0.0.1` never reaches a
 * network, which is why a local node with an API key needs no certificate.
 *
 * @property host Qdrant host.
 * @property port Qdrant port (1..65535).
 * @property apiKey API key sent as the `api-key` header; `null` disables auth.
 * @property bearerToken Qdrant JWT sent as `Authorization: Bearer`; mutually exclusive with [apiKey].
 * @property useTls use HTTPS instead of HTTP.
 * @property trustAnchors which certificates TLS will accept. The default is the platform's own store;
 *   see [TrustAnchors] for what each platform reads and which options it can honour. Ignored when
 *   [useTls] is false, because there is no certificate to judge.
 * @property requestTimeout per-request timeout (applies to each attempt).
 * @property connectTimeout timeout for establishing a connection; `null` uses the engine default.
 * @property socketTimeout maximum idle time between two data packets on an open connection; `null`
 *   uses the engine default.
 * @property maxRetries how many times to retry a retryable failure (HTTP 429/502/503/504 and
 *   transient I/O errors) with exponential backoff + jitter. `0` disables retries.
 * @property retryBaseDelay base delay before the first retry; each subsequent retry backs off
 *   exponentially, capped at [retryMaxDelay]. The server's `Retry-After` is honored when present.
 * @property retryMaxDelay upper bound on a single retry delay.
 * @property dispatcher dispatcher the client runs on; injectable for tests. The default is
 *   platform-dependent, see [ioDispatcher].
 */
// A settings bag is the one shape where a long parameter list is the API rather than a smell: every
// entry is an independent, defaulted knob, and grouping them into sub-objects would make the DSL that
// callers actually use worse to read. The DSL is the front door; this constructor is what it builds.
@Suppress("LongParameterList")
public class KdrantConfig(
    public val host: String,
    public val port: Int,
    public val apiKey: String? = null,
    public val useTls: Boolean = false,
    public val requestTimeout: Duration = 30.seconds,
    public val connectTimeout: Duration? = null,
    public val socketTimeout: Duration? = null,
    public val maxRetries: Int = 3,
    public val retryBaseDelay: Duration = 500.milliseconds,
    public val retryMaxDelay: Duration = 5.seconds,
    public val dispatcher: CoroutineDispatcher = ioDispatcher,
    public val bearerToken: String? = null,
    public val trustAnchors: TrustAnchors = TrustAnchors.System,
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535, was $port" }
        require(apiKey == null || bearerToken == null) {
            "set an apiKey or a bearerToken, not both: Qdrant reads one credential per request, and " +
                "sending two hides which one it honoured."
        }
        require(apiKey == null && bearerToken == null || useTls || isLoopback(host)) {
            "useTls must be true when a credential is set, otherwise it is sent over plaintext HTTP. " +
                "Set useTls = true, point the client at a loopback address, or drop the credential for " +
                "an unauthenticated node."
        }
        connectTimeout?.let { require(it.isPositive()) { "connectTimeout must be positive, was $it" } }
        socketTimeout?.let { require(it.isPositive()) { "socketTimeout must be positive, was $it" } }
        require(maxRetries >= 0) { "maxRetries must be >= 0, was $maxRetries" }
        require(useTls || trustAnchors == TrustAnchors.System) {
            "trustAnchors decides which certificate to accept, and a plaintext connection presents " +
                "none. Set useTls = true, or leave trustAnchors at TrustAnchors.System."
        }
        require(retryBaseDelay.isPositive()) { "retryBaseDelay must be positive, was $retryBaseDelay" }
        require(retryMaxDelay >= retryBaseDelay) {
            "retryMaxDelay ($retryMaxDelay) must be >= retryBaseDelay ($retryBaseDelay)"
        }
    }

    /** Renders the config without exposing either credential, so it is safe to log. */
    override fun toString(): String =
        "KdrantConfig(host=$host, port=$port, apiKey=${if (apiKey != null) "***" else "null"}, " +
            "bearerToken=${if (bearerToken != null) "***" else "null"}, " +
            "useTls=$useTls, trustAnchors=$trustAnchors, requestTimeout=$requestTimeout, " +
            "connectTimeout=$connectTimeout, " +
            "socketTimeout=$socketTimeout, maxRetries=$maxRetries, " +
            "retryBaseDelay=$retryBaseDelay, retryMaxDelay=$retryMaxDelay, dispatcher=$dispatcher)"
}

/**
 * Whether [host] names this machine, in which case a credential sent to it never crosses a network
 * and the TLS requirement has nothing to protect.
 *
 * Deliberately literal: it matches what a caller wrote rather than what a name resolves to. A
 * hostname that happens to have a loopback A record is still a name someone else's DNS can move.
 */
private fun isLoopback(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    return bare.equals("localhost", ignoreCase = true) ||
        bare == "::1" ||
        bare.startsWith("127.") &&
        bare.count { it == '.' } == 3
}

/** Mutable builder backing the `Kdrant(host, port) { ... }` configuration DSL. */
@KdrantDsl
public class KdrantConfigBuilder internal constructor(
    private val host: String,
    private val port: Int,
) {
    /** API key sent as the `api-key` header. `null` disables auth. */
    public var apiKey: String? = null

    /**
     * Qdrant JWT sent as `Authorization: Bearer`, for access narrower than the master key: read-only,
     * a named collection, or a payload filter scoping which points are visible. Mutually exclusive
     * with [apiKey].
     *
     * Minting and rotating tokens is Qdrant's business, not this client's — see
     * [Qdrant's JWT documentation](https://qdrant.tech/documentation/guides/security/#granular-access-control-with-jwt).
     */
    public var bearerToken: String? = null

    /**
     * Use HTTPS instead of HTTP. Required when sending a credential, unless the host is a loopback
     * address, where nothing leaves the machine.
     */
    public var useTls: Boolean = false

    /**
     * Which certificates TLS will accept: the platform's store by default, a PEM bundle for a private
     * CA or a self-signed node, or a set of pinned public keys. See [TrustAnchors] for what each
     * platform can honour, and which store each one reads.
     */
    public var trustAnchors: TrustAnchors = TrustAnchors.System

    /** Per-request timeout (applies to each attempt). */
    public var requestTimeout: Duration = 30.seconds

    /** Timeout for establishing a connection; `null` uses the engine default. */
    public var connectTimeout: Duration? = null

    /** Max idle time between two data packets on an open connection; `null` uses the engine default. */
    public var socketTimeout: Duration? = null

    /** How many times to retry a retryable failure (HTTP 429/502/503/504, transient I/O). `0` disables. */
    public var maxRetries: Int = 3

    /** Base delay before the first retry; subsequent retries back off exponentially up to [retryMaxDelay]. */
    public var retryBaseDelay: Duration = 500.milliseconds

    /** Upper bound on a single retry delay. */
    public var retryMaxDelay: Duration = 5.seconds

    /** Dispatcher used for client work. Injectable for tests; defaults to [ioDispatcher]. */
    public var dispatcher: CoroutineDispatcher = ioDispatcher

    internal fun build(): KdrantConfig =
        KdrantConfig(
            host, port, apiKey, useTls, requestTimeout, connectTimeout, socketTimeout,
            maxRetries, retryBaseDelay, retryMaxDelay, dispatcher, bearerToken, trustAnchors,
        )
}

/** Builds a [KdrantConfig] from the configuration DSL. Used by transport factories. */
public fun kdrantConfig(
    host: String,
    port: Int,
    configure: KdrantConfigBuilder.() -> Unit = {},
): KdrantConfig = KdrantConfigBuilder(host, port).apply(configure).build()
