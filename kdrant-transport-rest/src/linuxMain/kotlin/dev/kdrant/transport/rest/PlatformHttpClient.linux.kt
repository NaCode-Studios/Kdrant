package dev.kdrant.transport.rest

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import kotlin.time.Duration

/**
 * Curl. Ktor's CIO engine also compiles for Linux, but it has no TLS on Kotlin/Native, which rules
 * it out for anything but a loopback Qdrant. Curl links against the system libcurl and gets TLS,
 * proxies and the host's certificate store with it.
 *
 * The pool arguments are the JVM engine's and are ignored here.
 */
internal actual fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Curl) { configure() }
