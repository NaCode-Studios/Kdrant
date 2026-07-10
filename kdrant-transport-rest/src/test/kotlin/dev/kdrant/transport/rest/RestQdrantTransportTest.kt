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
            kdrantConfig("qdrant.example", 6333) { apiKey = "secret" },
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
    fun `5xx maps to Transport`() {
        val t = transport { respond("", HttpStatusCode.InternalServerError) }
        assertThrows(KdrantException.Transport::class.java) {
            runBlocking { t.use { it.deleteCollection("docs") } }
        }
    }
}
