package dev.kdrant.transport.rest

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import kotlin.time.Duration

/** CIO, the engine every Kdrant release before the module went multiplatform used on the JVM. */
internal actual fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(CIO) {
        configure()
        engine {
            maxConnectionsPerRoute?.let { endpoint.maxConnectionsPerRoute = it }
            keepAliveTime?.let { endpoint.keepAliveTime = it.inWholeMilliseconds }
        }
    }
