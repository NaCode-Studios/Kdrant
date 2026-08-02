package dev.kdrant.transport.rest

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlin.time.Duration

/**
 * Darwin, which is NSURLSession. It is the engine an iOS or macOS app should be on: the platform
 * owns the connection pool, the proxy settings and the trust store, and a second HTTP stack in the
 * process would honour none of them.
 *
 * The pool arguments are the JVM engine's and are ignored here.
 */
internal actual fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Darwin) { configure() }
