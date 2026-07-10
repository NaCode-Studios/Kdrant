@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.kdrantConfig
import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.PointId
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
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectionAndPointOpsTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun transport(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RestQdrantTransport(kdrantConfig("h", 6333) {}, MockEngine { request -> handler(request) })

    @Test
    fun `collectionExists issues GET and reads the boolean`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":{"exists":true},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val exists = t.use { runBlocking { it.collectionExists("docs") } }

        assertEquals(HttpMethod.Get, captured.method)
        assertEquals("/collections/docs/exists", captured.url.encodedPath)
        assertTrue(exists)
    }

    @Test
    fun `getCollection reads status and counts`() {
        val t = transport {
            respond(
                """{"result":{"status":"green","points_count":7,"segments_count":2},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val info = t.use { runBlocking { it.getCollection("docs") } }

        assertEquals(CollectionStatus.GREEN, info.status)
        assertEquals(7L, info.pointsCount)
    }

    @Test
    fun `count posts the exact flag and reads the count`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":{"count":123},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val n = t.use { runBlocking { it.count("docs", filter = null, exact = false) } }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/points/count", captured.url.encodedPath)
        assertEquals(123L, n)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("exact"))
    }

    @Test
    fun `retrieve posts ids and maps the record array`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":[{"id":1,"payload":{"t":"x"}},{"id":"u"}],"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val records = t.use {
            runBlocking { it.retrieve("docs", listOf(PointId.num(1), PointId.uuid("u")), withPayload = null, withVector = null) }
        }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/points", captured.url.encodedPath)
        assertEquals(2, records.size)
        assertEquals(PointId.num(1), records[0].id)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("ids"))
    }
}
