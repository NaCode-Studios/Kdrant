package dev.kdrant.transport.rest

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
 */
internal expect fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient
