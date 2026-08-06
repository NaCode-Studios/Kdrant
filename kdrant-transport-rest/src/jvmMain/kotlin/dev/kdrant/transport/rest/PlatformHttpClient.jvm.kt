package dev.kdrant.transport.rest

import dev.kdrant.TrustAnchors
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration

/** CIO, the engine every Kdrant release before the module went multiplatform used on the JVM. */
internal actual fun platformHttpClient(
    maxConnectionsPerRoute: Int?,
    keepAliveTime: Duration?,
    trustAnchors: TrustAnchors,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(CIO) {
        configure()
        engine {
            maxConnectionsPerRoute?.let { endpoint.maxConnectionsPerRoute = it }
            keepAliveTime?.let { endpoint.keepAliveTime = it.inWholeMilliseconds }
            trustManagerFor(trustAnchors)?.let { manager -> https { trustManager = manager } }
        }
    }

/**
 * The trust manager for a decision, or `null` for [TrustAnchors.System], where leaving CIO alone gets
 * the JDK's own `cacerts` and whatever a deployment has already added to it.
 */
private fun trustManagerFor(anchors: TrustAnchors): X509TrustManager? = when (anchors) {
    TrustAnchors.System -> null
    is TrustAnchors.Pem -> pemTrustManager(anchors.certificates)
    is TrustAnchors.Pinned -> PinnedPublicKeyTrustManager(anchors.sha256)
}

/**
 * Trusts the supplied certificates and nothing else — not the JDK's store as well.
 *
 * A caller who names a company CA is describing the whole set of certificates they expect. Adding the
 * public roots back would mean any publicly issued certificate for the same hostname passed too, which
 * is most of what naming a private CA was meant to prevent.
 */
private fun pemTrustManager(pem: String): X509TrustManager {
    val factory = CertificateFactory.getInstance("X.509")
    val certificates = ByteArrayInputStream(pem.toByteArray()).use { factory.generateCertificates(it) }
    require(certificates.isNotEmpty()) { "the PEM bundle parsed to no certificates" }

    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate -> setCertificateEntry("kdrant-$index", certificate) }
    }
    val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers
    return managers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: error("the platform's TrustManagerFactory produced no X509TrustManager")
}

/**
 * Trusts a chain when the SHA-256 of any certificate's subject public key info is pinned.
 *
 * Any certificate in the chain rather than only the leaf, which is what makes pinning an intermediate
 * or a root possible — usually the better choice, because it survives the leaf being reissued.
 *
 * The chain's own signatures are deliberately not checked. A pin is the stronger statement: it names
 * one key, where a signature only says that some trusted authority vouched for it.
 */
private class PinnedPublicKeyTrustManager(private val pins: Set<String>) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("this trust manager verifies servers, not clients")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.map { pinOf(it) }.orEmpty()
        if (presented.none { it in pins }) {
            throw CertificateException(
                "no certificate in the chain matched a configured public-key pin " +
                    "(presented ${presented.size}, pinned ${pins.size})",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun pinOf(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
        )
}
