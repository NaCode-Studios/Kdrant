package dev.kdrant.transport.rest

import dev.kdrant.KdrantException
import dev.kdrant.kdrantConfig
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.Distance
import dev.kdrant.model.VectorsConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the REST engine using Ktor's [MockEngine] — verifies request shaping and the
 * HTTP-status -> [KdrantException] mapping without a real server (that's the integration test).
 * Complements [CollectionsIntegrationTest].
 */
class RestQdrantTransportTest {

    private val sampleRequest =
        CreateCollectionRequest(VectorsConfig.single(768, Distance.COSINE), onDiskPayload = true)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun transport(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): RestQdrantTransport {
        val engine = MockEngine { request -> handler(request) }
        return RestQdrantTransport(
            kdrantConfig("qdrant.example", 6333) { apiKey = "secret"; useTls = true; maxRetries = 0 },
            engine,
        )
    }

    @Test
    fun `createCollection issues PUT with the api-key header and serialized body`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use { runBlocking { it.createCollection("docs", sampleRequest) } }

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/collections/docs", captured.url.encodedPath)
        assertEquals("secret", captured.headers["api-key"])
        val body = (captured.body as TextContent).text
        assertTrue(body.contains("\"size\":768"), "body should carry the vector size: $body")
        assertTrue(body.contains("\"on_disk_payload\":true"), "body should carry on_disk_payload: $body")
    }

    @Test
    fun `deleteCollection issues DELETE to the collection path`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use { runBlocking { it.deleteCollection("docs") } }

        assertEquals(HttpMethod.Delete, captured.method)
        assertEquals("/collections/docs", captured.url.encodedPath)
    }

    @Test
    fun `404 maps to CollectionNotFound carrying the collection name`() {
        val t = transport { respond("", HttpStatusCode.NotFound) }
        val ex = assertThrows(KdrantException.CollectionNotFound::class.java) {
            runBlocking { t.use { it.deleteCollection("ghost") } }
        }
        assertEquals("ghost", ex.collection)
    }

    @Test
    fun `404 preserves the server error message`() {
        val t = transport {
            respond(
                """{"status":{"error":"Collection `ghost` doesn't exist!"},"time":0.0}""",
                HttpStatusCode.NotFound,
                jsonHeaders,
            )
        }
        val ex = assertThrows(KdrantException.CollectionNotFound::class.java) {
            runBlocking { t.use { it.getCollection("ghost") } }
        }
        assertEquals("ghost", ex.collection)
        assertTrue(ex.message!!.contains("doesn't exist"), "message was: ${ex.message}")
    }

    @Test
    fun `4xx maps to InvalidRequest with the server error message`() {
        val t = transport {
            respond(
                """{"status":{"error":"Wrong vector size"},"time":0.1}""",
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }
        val ex = assertThrows(KdrantException.InvalidRequest::class.java) {
            runBlocking { t.use { it.createCollection("docs", sampleRequest) } }
        }
        assertTrue(ex.message!!.contains("Wrong vector size"), "message was: ${ex.message}")
    }

    @Test
    fun `401 maps to Unauthorized`() {
        val t = transport { respond("", HttpStatusCode.Unauthorized) }
        assertThrows(KdrantException.Unauthorized::class.java) {
            runBlocking { t.use { it.createCollection("docs", sampleRequest) } }
        }
    }

    @Test
    fun `5xx maps to ServerError`() {
        val t = transport { respond("", HttpStatusCode.InternalServerError) }
        assertThrows(KdrantException.ServerError::class.java) {
            runBlocking { t.use { it.deleteCollection("docs") } }
        }
    }

    @Test
    fun `409 maps to AlreadyExists carrying the server message`() {
        val t = transport {
            respond(
                """{"status":{"error":"Collection `docs` already exists!"},"time":0.0}""",
                HttpStatusCode.Conflict,
                jsonHeaders,
            )
        }
        val ex = assertThrows(KdrantException.AlreadyExists::class.java) {
            runBlocking { t.use { it.createCollection("docs", sampleRequest) } }
        }
        assertTrue(ex.message!!.contains("already exists"), "message was: ${ex.message}")
    }

    @Test
    fun `429 maps to RateLimited and reads Retry-After`() {
        val t = transport {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
        }
        val ex = assertThrows(KdrantException.RateLimited::class.java) {
            runBlocking { t.use { it.getCollection("docs") } }
        }
        assertEquals(2.seconds, ex.retryAfter)
    }

    @Test
    fun `503 maps to ServiceUnavailable`() {
        val t = transport { respond("", HttpStatusCode.ServiceUnavailable) }
        assertThrows(KdrantException.ServiceUnavailable::class.java) {
            runBlocking { t.use { it.deleteCollection("docs") } }
        }
    }

    @Test
    fun `408 maps to Timeout`() {
        val t = transport { respond("", HttpStatusCode.RequestTimeout) }
        assertThrows(KdrantException.Timeout::class.java) {
            runBlocking { t.use { it.deleteCollection("docs") } }
        }
    }

    @Test
    fun `retries a retryable 503 then succeeds`() {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            if (calls == 1) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val t = RestQdrantTransport(
            kdrantConfig("h", 6333) { retryBaseDelay = 1.milliseconds; retryMaxDelay = 2.milliseconds },
            engine,
        )
        t.use { runBlocking { it.deleteCollection("docs") } }
        assertEquals(2, calls)
    }

    @Test
    fun `does not retry a non-retryable 400`() {
        var calls = 0
        val engine = MockEngine { _ -> calls++; respond("", HttpStatusCode.BadRequest) }
        val t = RestQdrantTransport(
            kdrantConfig("h", 6333) { retryBaseDelay = 1.milliseconds; retryMaxDelay = 2.milliseconds },
            engine,
        )
        assertThrows(KdrantException.InvalidRequest::class.java) {
            runBlocking { t.use { it.deleteCollection("docs") } }
        }
        assertEquals(1, calls)
    }
}
