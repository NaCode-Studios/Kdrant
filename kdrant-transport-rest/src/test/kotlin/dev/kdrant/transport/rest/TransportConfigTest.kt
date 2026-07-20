@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.kdrantConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M21 granular-transport & observability wiring: the client hook and api-key-redacting logging. */
class TransportConfigTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `configureClient hook can add a request header`() {
        lateinit var captured: HttpRequestData
        val t = RestQdrantTransport(
            kdrantConfig("h", 6333) {},
            MockEngine { request ->
                captured = request
                respond("""{"result":{"exists":true},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            },
            configureClient = { defaultRequest { headers.append("X-Trace", "abc") } },
        )
        t.use { runBlocking { it.collectionExists("docs") } }

        assertEquals("abc", captured.headers["X-Trace"])
    }

    @Test
    fun `logging redacts the api-key header`() {
        val lines = mutableListOf<String>()
        val recording = object : Logger {
            override fun log(message: String) {
                lines.add(message)
            }
        }
        val t = RestQdrantTransport(
            kdrantConfig("h", 6333) { apiKey = "SUPER-SECRET-KEY"; useTls = true },
            MockEngine { respond("""{"result":{"exists":true},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders) },
            logLevel = LogLevel.ALL,
            logger = recording,
        )
        t.use { runBlocking { it.collectionExists("docs") } }

        val log = lines.joinToString("\n")
        assertTrue(log.contains("api-key"), "expected the api-key header to be logged (redacted)")
        assertTrue(log.contains("***"), "expected a redaction marker in the log")
        assertFalse(log.contains("SUPER-SECRET-KEY"), "the API key value must never reach the logs")
    }
}
