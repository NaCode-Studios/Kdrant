@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.KdrantException
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.kdrantConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M39: the bearer credential on the wire, and the refusal a scoped token gets back. */
class ScopedAccessTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `a bearer token is sent as Authorization, and no api-key header goes with it`() {
        var authorization: String? = null
        var apiKey: String? = null
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) { bearerToken = "a.token.value"; useTls = true },
            MockEngine { request ->
                authorization = request.headers[HttpHeaders.Authorization]
                apiKey = request.headers["api-key"]
                respond("""{"result":{"exists":true},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            },
        )
        transport.use { runBlocking { it.collectionExists("docs") } }

        assertEquals("Bearer a.token.value", authorization)
        assertNull(apiKey)
    }

    @Test
    fun `a 403 is a Forbidden naming the collection, not a generic transport failure`() {
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) { bearerToken = "read.only.token"; useTls = true },
            MockEngine {
                respond(
                    """{"status":{"error":"Write access denied"},"time":0.0}""",
                    HttpStatusCode.Forbidden,
                    jsonHeaders,
                )
            },
        )

        val error = transport.use {
            runCatching { runBlocking { it.deleteCollection("docs") } }.exceptionOrNull()
        }

        assertTrue(error is KdrantException.Forbidden, "expected Forbidden, got ${error?.let { it::class }}")
        // Callers that were written against 2.0.0 catch Unauthorized; they must keep catching this.
        assertTrue(
            KdrantException.Unauthorized::class.java.isInstance(error),
            "Forbidden must stay catchable as Unauthorized",
        )
        val forbidden = error as KdrantException.Forbidden
        assertEquals("docs", forbidden.collection)
        assertTrue(forbidden.message!!.contains("Write access denied"), "the server's reason should survive")
    }

    @Test
    fun `a 401 stays Unauthorized rather than becoming Forbidden`() {
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) {},
            MockEngine { respond("""{"status":{"error":"Must provide an API key"}}""", HttpStatusCode.Unauthorized) },
        )

        val error = transport.use {
            runCatching { runBlocking { it.collectionExists("docs") } }.exceptionOrNull()
        }

        assertTrue(error is KdrantException.Unauthorized)
        assertFalse(error is KdrantException.Forbidden)
    }

    @Test
    fun `logging redacts the Authorization header`() {
        val lines = mutableListOf<String>()
        val recording = object : Logger {
            override fun log(message: String) {
                lines.add(message)
            }
        }
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) { bearerToken = "SUPER.SECRET.TOKEN"; useTls = true },
            MockEngine { respond("""{"result":{"exists":true},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders) },
            logLevel = LogLevel.ALL,
            logger = recording,
        )
        transport.use { runBlocking { it.collectionExists("docs") } }

        val log = lines.joinToString("\n")
        assertTrue(log.contains("***"), "expected a redaction marker in the log")
        assertFalse(log.contains("SUPER.SECRET.TOKEN"), "the token must never reach the logs")
    }

    @Test
    fun `the two credentials are mutually exclusive`() {
        assertThrows(IllegalArgumentException::class.java) {
            kdrantConfig("h", 6333) { apiKey = "k"; bearerToken = "t"; useTls = true }
        }
    }

    @Test
    fun `a credential needs TLS unless it is going to this machine`() {
        assertThrows(IllegalArgumentException::class.java) {
            kdrantConfig("qdrant.example.com", 6333) { bearerToken = "t" }
        }
        // Nothing leaves the host, so there is nothing to intercept.
        kdrantConfig("localhost", 6333) { bearerToken = "t" }
        kdrantConfig("127.0.0.1", 6333) { apiKey = "k" }
    }
}
