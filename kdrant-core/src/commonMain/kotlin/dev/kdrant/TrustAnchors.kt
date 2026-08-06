package dev.kdrant

/**
 * Which certificates a client is willing to trust for TLS.
 *
 * `useTls` used to be the whole story, and with one engine and a JVM caller it was enough: anything
 * unusual was reachable through `configureClient`, which hands back Ktor's `HttpClientConfig<*>`. A
 * star projection cannot open an engine-specific block, so that escape hatch stopped working the day
 * the transport went multiplatform — on three of the four targets it was never available at all.
 *
 * The cases this exists for are ordinary. Qdrant Cloud serves a public certificate and needs nothing
 * here. A self-hosted cluster behind a company CA, a staging node with a self-signed certificate, and
 * anything that wants certificate pinning are all normal deployments, and all of them ended at "use
 * the JVM".
 *
 * ### Which store each platform reads
 *
 * [System] is not one store. It is whichever store the platform keeps, and knowing which one is the
 * difference between adding a certificate in the right place and adding it twice in the wrong ones:
 *
 * | Target | Engine | System trust means |
 * | --- | --- | --- |
 * | JVM | CIO | the JDK's `cacerts` truststore |
 * | iOS, macOS | Darwin | the system keychain, plus App Transport Security |
 * | Linux | Curl | the CA bundle libcurl was built against, usually `/etc/ssl/certs` |
 * | Windows | WinHttp | the machine and user certificate stores |
 *
 * ### What each platform supports
 *
 * | | JVM | Linux | iOS, macOS | Windows |
 * | --- | --- | --- | --- | --- |
 * | [System] | yes | yes | yes | yes |
 * | [Pem] | yes | yes | no | no |
 * | [Pinned] | yes | no | no | no |
 *
 * A combination a target cannot honour is refused when the client is built, with a message naming the
 * platform and the store to put the certificate in. Silently falling back to system trust would be the
 * worst of the options: the connection would succeed, and the caller would believe they had pinned it.
 */
public sealed interface TrustAnchors {

    /** Whatever the platform trusts. The default, and correct for any publicly issued certificate. */
    public data object System : TrustAnchors

    /**
     * Trust exactly the certificates in a PEM bundle, and nothing else — the company CA, or the
     * self-signed certificate a staging node serves.
     *
     * @property certificates one or more `-----BEGIN CERTIFICATE-----` blocks, concatenated.
     */
    public data class Pem(public val certificates: String) : TrustAnchors {
        init {
            require(certificates.contains(BEGIN_CERTIFICATE)) {
                "TrustAnchors.Pem takes PEM text, and this has no $BEGIN_CERTIFICATE block in it. " +
                    "A DER file has to be converted first: openssl x509 -inform der -in ca.der -out ca.pem"
            }
        }

        /** Redacted: a trust bundle is not secret, and a log line with a certificate in it is still noise. */
        override fun toString(): String = "Pem(certificates=<${certificates.count(BEGIN_CERTIFICATE)} certificate(s)>)"

        private fun String.count(marker: String): Int = split(marker).size - 1
    }

    /**
     * Trust a certificate only if the SHA-256 of its subject public key info matches one of [sha256].
     *
     * Pinning the key rather than the certificate is what lets a server renew without the client being
     * rebuilt, as long as the key is kept. Pin more than one: a pin set with a single entry becomes an
     * outage the moment that key has to be rotated in a hurry.
     *
     * ```bash
     * openssl x509 -in server.pem -pubkey -noout |
     *   openssl pkey -pubin -outform der |
     *   openssl dgst -sha256 -binary | base64
     * ```
     *
     * @property sha256 base64-encoded SHA-256 hashes, in the form the command above prints.
     */
    public data class Pinned(public val sha256: Set<String>) : TrustAnchors {
        init {
            require(sha256.isNotEmpty()) {
                "TrustAnchors.Pinned with no pins would trust nothing and refuse every connection"
            }
        }
    }

    private companion object {
        const val BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----"
    }
}
