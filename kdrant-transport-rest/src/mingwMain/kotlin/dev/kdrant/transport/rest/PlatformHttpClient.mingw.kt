package dev.kdrant.transport.rest

import dev.kdrant.TrustAnchors
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp
import kotlin.time.Duration

/**
 * WinHttp, the system HTTP stack. Curl would work on Windows too, but only if libcurl is installed,
 * and WinHttp is already there and already trusts what the machine trusts.
 *
 * Trust is the machine and user certificate stores, and WinHttp offers no per-handle root override, so
 * a private CA goes into the store — `certutil -addstore Root ca.pem`, or the group policy that does it
 * for every machine at once, which is where an organisation wants that decision anyway.
 *
 * The pool arguments are the JVM engine's and are ignored here.
 */
internal actual fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    trustAnchors: TrustAnchors,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    if (trustAnchors != TrustAnchors.System) {
        unsupportedTrustAnchors(
            trustAnchors,
            target = "Windows",
            store = "the machine and user certificate stores WinHttp reads",
        )
    }
    return HttpClient(WinHttp) { configure() }
}
