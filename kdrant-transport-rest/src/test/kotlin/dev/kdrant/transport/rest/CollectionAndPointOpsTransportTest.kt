@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.dsl.filter
import dev.kdrant.dsl.payloadOf
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.kdrantConfig
import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointId
import dev.kdrant.model.PointVectors
import dev.kdrant.model.VectorData
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `collectionExists returns false on 404 instead of throwing`() {
        val t = transport { respond("", HttpStatusCode.NotFound) }
        val exists = t.use { runBlocking { it.collectionExists("ghost") } }
        assertFalse(exists)
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

    @Test
    fun `createPayloadIndex PUTs the field name and schema`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use { runBlocking { it.createPayloadIndex("docs", "lang", PayloadSchemaType.KEYWORD, wait = true) } }

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/collections/docs/index", captured.url.encodedPath)
        assertEquals("true", captured.url.parameters["wait"])
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertEquals("lang", body["field_name"]!!.jsonPrimitive.content)
        assertEquals("keyword", body["field_schema"]!!.jsonPrimitive.content)
    }

    @Test
    fun `setPayload posts the payload with a points selector`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use {
            runBlocking {
                it.setPayload("docs", payloadOf("lang" to "en"), DeleteSelector.Ids(listOf(PointId.num(1))), key = null, wait = false)
            }
        }

        assertEquals("/collections/docs/points/payload", captured.url.encodedPath)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("payload"))
        assertTrue(body.containsKey("points"))
    }

    @Test
    fun `clearPayload posts a filter selector`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use {
            runBlocking { it.clearPayload("docs", DeleteSelector.ByFilter(filter { must { "lang" eq "en" } }), wait = false) }
        }

        assertEquals("/collections/docs/points/payload/clear", captured.url.encodedPath)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("filter"))
    }

    @Test
    fun `updateVectors PUTs the points with their vectors`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use {
            runBlocking {
                it.updateVectors(
                    "docs",
                    listOf(PointVectors(PointId.num(1), VectorData.Named(mapOf("text" to VectorData.Dense(listOf(0.1f)))))),
                    wait = false,
                )
            }
        }

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/collections/docs/points/vectors", captured.url.encodedPath)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("points"))
    }

    @Test
    fun `deleteVectors posts vector names with a selector`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use {
            runBlocking { it.deleteVectors("docs", listOf("image"), DeleteSelector.Ids(listOf(PointId.num(1))), wait = false) }
        }

        assertEquals("/collections/docs/points/vectors/delete", captured.url.encodedPath)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("vector"))
        assertTrue(body.containsKey("points"))
    }
}
