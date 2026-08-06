package dev.kdrant.transport.rest

import dev.kdrant.TrustAnchors
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkstemp
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
    trustAnchors: TrustAnchors,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Curl) {
    configure()
    when (trustAnchors) {
        TrustAnchors.System -> Unit
        // libcurl reads a CA bundle from a path rather than from memory, so the bundle is written to a
        // file. It lives as long as the process, which is the lifetime every connection the client
        // makes needs it for.
        is TrustAnchors.Pem -> engine { caInfo = writeTrustBundle(trustAnchors.certificates) }
        // libcurl can pin — CURLOPT_PINNEDPUBLICKEY — and Ktor's Curl engine exposes no option for it.
        // Saying so beats both alternatives: quietly using system trust, or reaching into libcurl
        // behind the engine's back to set an option on a handle the engine owns.
        is TrustAnchors.Pinned -> unsupportedTrustAnchors(
            trustAnchors,
            target = "Linux",
            store = "the CA bundle libcurl was built against, and Ktor's Curl engine exposes no pinning option",
        )
    }
}

/**
 * Writes [pem] to a private temporary file and returns its path.
 *
 * `mkstemp` rather than a name built by hand: it creates the file exclusively, with mode 0600, and
 * fails instead of reusing one — so nothing can race between choosing a name and writing to it and
 * leave the client trusting a bundle somebody else put there.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeTrustBundle(pem: String): String = memScoped {
    val directory = getenv("TMPDIR")?.toKString()?.trimEnd('/')?.ifBlank { null } ?: "/tmp"
    val template = "$directory/kdrant-ca-XXXXXX"
    val path = allocArray<ByteVar>(template.length + 1)
    template.encodeToByteArray().forEachIndexed { index, byte -> path[index] = byte }
    path[template.length] = 0

    val descriptor = mkstemp(path)
    check(descriptor >= 0) {
        "could not create a temporary file for the TLS trust bundle in $directory; " +
            "set TMPDIR to a writable directory"
    }
    val file = fdopen(descriptor, "w")
    if (file == null) {
        close(descriptor)
        error("could not open the TLS trust bundle for writing in $directory")
    }
    try {
        check(fputs(pem, file) >= 0) { "could not write the TLS trust bundle in $directory" }
    } finally {
        fclose(file)
    }
    path.toKString()
}
