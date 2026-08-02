package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.createCollection
import dev.kdrant.createCollectionIfNotExists
import dev.kdrant.getCollectionOrNull
import dev.kdrant.kdrantConfig
import dev.kdrant.model.Distance
import dev.kdrant.searchAs
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests for the M12 DX extensions (typed payloads + collection conveniences) over the REST engine. */
class ClientDxTransportTest {

    @Serializable
    private data class Article(val title: String, val lang: String)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine): QdrantClient =
        QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))

    @Test
    fun `searchAs decodes hit payloads into typed objects`() {
        val engine = MockEngine {
            respond(
                """{"result":{"points":[
                    {"id":1,"score":0.9,"payload":{"title":"Intro","lang":"en"}},
                    {"id":2,"score":0.5}
                ]}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val hits = client(engine).use {
            runBlocking { it.searchAs<Article>("docs") { query(listOf(0.1f, 0.2f)) } }
        }
        assertEquals(2, hits.size)
        assertEquals(Article("Intro", "en"), hits[0].payload)
        assertNull(hits[1].payload)
    }

    @Test
    fun `getCollectionOrNull returns null on 404`() {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        assertNull(client(engine).use { runBlocking { it.getCollectionOrNull("ghost") } })
    }

    @Test
    fun `createCollectionIfNotExists returns false when the collection already exists`() {
        val engine = MockEngine {
            respond("""{"status":{"error":"already exists"},"time":0.0}""", HttpStatusCode.Conflict, jsonHeaders)
        }
        val created = client(engine).use {
            runBlocking { it.createCollectionIfNotExists("docs") { vector { size = 4; distance = Distance.COSINE } } }
        }
        assertFalse(created)
    }

    @Test
    fun `createCollectionIfNotExists returns true when it creates the collection`() {
        val engine = MockEngine { respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders) }
        val created = client(engine).use {
            runBlocking { it.createCollectionIfNotExists("docs") { vector { size = 4; distance = Distance.COSINE } } }
        }
        assertTrue(created)
    }

    @Test
    fun `createCollection size-distance overload posts the vector config`() {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { req ->
            captured = req
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).use { runBlocking { it.createCollection("docs", size = 768, distance = Distance.DOT) } }
        val body = (captured.body as TextContent).text
        assertTrue(body.contains("\"size\":768"), "body: $body")
        assertTrue(body.contains("Dot"), "body: $body")
    }
}
