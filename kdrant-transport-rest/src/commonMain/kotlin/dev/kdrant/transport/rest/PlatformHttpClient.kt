package dev.kdrant.transport.rest

import dev.kdrant.TrustAnchors
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration

/**
 * The Ktor engine this target speaks HTTP over, already carrying [configure].
 *
 * Ktor's client API is identical across engines, so this is the only declaration in the module that
 * has to know which platform it is on:
 *
 * | Target | Engine | What it means for you |
 * | --- | --- | --- |
 * | JVM | CIO | Unchanged from every previous release. |
 * | iOS, macOS | Darwin | NSURLSession, so App Transport Security applies: a plaintext `http://` Qdrant is refused by the platform before Kdrant sees the request. Use TLS, or declare the ATS exception yourself. |
 * | Linux | Curl | Links against libcurl, which must be present on the host. It ships with the mainstream distributions; a slim container image may not have it. |
 * | Windows | WinHttp | Uses the system's HTTP stack and its certificate store. |
 *
 * [maxConnectionsPerRoute] and [keepAliveTime] tune a connection pool the JVM engine owns and the
 * native engines do not expose; they are ignored off the JVM rather than silently emulated.
 *
 * [trustAnchors] is the one argument here that must never be quietly ignored. An engine that cannot
 * honour the trust decision it was given throws, because the alternative is a connection that succeeds
 * against system trust while the caller believes it is pinned. See [TrustAnchors] for the support
 * matrix and the store each platform reads.
 */
internal expect fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    trustAnchors: TrustAnchors,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient

/**
 * The message an engine raises for a trust decision it cannot make, stated once so the four of them
 * cannot drift into four different explanations of the same limit.
 */
internal fun unsupportedTrustAnchors(anchors: TrustAnchors, target: String, store: String): Nothing =
    throw IllegalArgumentException(
        "${anchors::class.simpleName} is not supported on $target, where TLS trust comes from $store. " +
            "Add the certificate to that store, or use TrustAnchors.System. Kdrant refuses rather " +
            "than falling back, because a fallback would look like the trust decision was honoured.",
    )
