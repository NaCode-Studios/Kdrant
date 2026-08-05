package dev.kdrant.transport.rest

import dev.kdrant.TrustAnchors
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlin.time.Duration

/**
 * Darwin, which is NSURLSession. It is the engine an iOS or macOS app should be on: the platform
 * owns the connection pool, the proxy settings and the trust store, and a second HTTP stack in the
 * process would honour none of them.
 *
 * That ownership is also why a caller-supplied trust bundle is not accepted here. NSURLSession decides
 * trust from the system keychain and App Transport Security, and a private CA belongs in the keychain
 * — installed by a configuration profile or added by the app at launch — rather than passed to one
 * HTTP client inside the process. Overriding it per-request would mean re-implementing the platform's
 * trust evaluation in a vector-database client, which is not a place anyone should be looking for it.
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
            target = "iOS and macOS",
            store = "the system keychain, as evaluated by NSURLSession under App Transport Security",
        )
    }
    return HttpClient(Darwin) { configure() }
}
