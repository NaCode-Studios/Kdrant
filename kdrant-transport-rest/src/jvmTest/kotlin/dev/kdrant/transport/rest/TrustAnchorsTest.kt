package dev.kdrant.transport.rest

import dev.kdrant.KdrantException
import dev.kdrant.TrustAnchors
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * A caller-supplied trust decision, against a server whose certificate nothing trusts by default.
 *
 * The certificate is generated in memory for the run, which is what makes this a real check rather
 * than a shaped one: nothing in the JDK's store vouches for it, so a client that connects has honoured
 * the bundle it was handed, and a client that connects with the wrong bundle would be a bug this test
 * would miss if the certificate were a public one.
 *
 * It is not a Qdrant. TLS is decided below the protocol, and standing up a Qdrant with certificates
 * would test Docker's volume mounts. The CI job that runs the client contract against a TLS Qdrant is
 * where the two meet.
 *
 * The server is Netty because Ktor's CIO server has no HTTPS at all. That is a fact about the test
 * harness and not about the client, which is CIO on the JVM either way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustAnchorsTest {

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var keyStoreFile: File
    private lateinit var certificate: X509Certificate
    private var port = 0

    @BeforeAll
    fun startTlsServer() {
        val keyStore = buildKeyStore {
            certificate("kdrant") {
                password = PASSWORD
                domains = listOf("localhost", "127.0.0.1")
            }
        }
        keyStoreFile = File.createTempFile("kdrant-test", ".jks").apply { deleteOnExit() }
        keyStore.store(keyStoreFile.outputStream(), PASSWORD.toCharArray())
        certificate = keyStore.getCertificate("kdrant") as X509Certificate
        port = ServerSocket(0).use { it.localPort }

        server = embeddedServer(
            Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "kdrant",
                    keyStorePassword = { PASSWORD.toCharArray() },
                    privateKeyPassword = { PASSWORD.toCharArray() },
                ) {
                    this.port = this@TrustAnchorsTest.port
                    keyStorePath = keyStoreFile
                }
            },
        ) {
            routing {
                get("/collections") {
                    call.respondText(
                        """{"result":{"collections":[{"name":"docs"}]},"status":"ok"}""",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
            }
        }.also { it.start(wait = false) }
    }

    @AfterAll
    fun stopTlsServer() {
        if (::server.isInitialized) server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    private fun listCollections(anchors: TrustAnchors) = Kdrant(host = "localhost", port = port) {
        useTls = true
        trustAnchors = anchors
        maxRetries = 0
    }.use { runBlocking { it.listCollections() } }

    @Test
    fun `a self-signed certificate is refused by default, which is the behaviour worth keeping`() {
        val failure = assertThrows(KdrantException::class.java) { listCollections(TrustAnchors.System) }

        assertNotNull(failure.message)
    }

    @Test
    fun `a PEM bundle naming that certificate completes a real request`() {
        val collections = listCollections(TrustAnchors.Pem(pemOf(certificate)))

        assertEquals(listOf("docs"), collections.map { it.name })
    }

    @Test
    fun `a PEM bundle naming a different certificate is refused`() {
        val other = buildKeyStore {
            certificate("other") { password = PASSWORD; domains = listOf("localhost") }
        }.getCertificate("other") as X509Certificate

        assertThrows(KdrantException::class.java) { listCollections(TrustAnchors.Pem(pemOf(other))) }
    }

    @Test
    fun `a matching public-key pin completes a real request`() {
        val collections = listCollections(TrustAnchors.Pinned(setOf(pinOf(certificate))))

        assertEquals(listOf("docs"), collections.map { it.name })
    }

    @Test
    fun `a pin that does not match is refused, which is the whole point of pinning`() {
        val failure = assertThrows(KdrantException::class.java) {
            listCollections(TrustAnchors.Pinned(setOf("Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm8=")))
        }

        assertInstanceOf(KdrantException::class.java, failure)
    }

    @Test
    fun `a trust decision without TLS is refused at construction, because there is no certificate to judge`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Kdrant(host = "localhost", port = port) { trustAnchors = TrustAnchors.Pem(pemOf(certificate)) }
        }

        assertEquals(true, failure.message?.contains("useTls"))
    }

    @Test
    fun `text that is not PEM is refused where the caller can still fix it`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            TrustAnchors.Pem("MIIC... this is DER in base64, not PEM")
        }

        assertEquals(true, failure.message?.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun `an empty pin set is refused rather than trusting nothing at connection time`() {
        assertThrows(IllegalArgumentException::class.java) { TrustAnchors.Pinned(emptySet()) }
    }

    private fun pemOf(certificate: X509Certificate): String = buildString {
        appendLine("-----BEGIN CERTIFICATE-----")
        appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded))
        appendLine("-----END CERTIFICATE-----")
    }

    private fun pinOf(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
        )

    private companion object {
        const val PASSWORD = "kdrant-test"

        @Suppress("unused")
        val keyStoreType: String = KeyStore.getDefaultType()
    }
}
