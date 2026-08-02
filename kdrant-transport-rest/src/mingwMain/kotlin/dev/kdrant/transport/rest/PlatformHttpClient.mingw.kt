package dev.kdrant.transport.rest

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp
import kotlin.time.Duration

/**
 * WinHttp, the system HTTP stack. Curl would work on Windows too, but only if libcurl is installed,
 * and WinHttp is already there and already trusts what the machine trusts.
 *
 * The pool arguments are the JVM engine's and are ignored here.
 */
internal actual fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(WinHttp) { configure() }
