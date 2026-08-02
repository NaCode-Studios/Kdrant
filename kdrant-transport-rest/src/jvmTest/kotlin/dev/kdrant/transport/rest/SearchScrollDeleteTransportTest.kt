@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.filter
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.kdrantConfig
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.PointId
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.SearchRequest
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchScrollDeleteTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val okBody = """{"result":true,"status":"ok"}"""

    private fun transport(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RestQdrantTransport(kdrantConfig("h", 6333) {}, MockEngine { request -> handler(request) })

    @Test
    fun `query posts to points-query and maps the hits`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":{"points":[{"id":1,"score":0.9,"payload":{"t":"x"}},{"id":"u","score":0.5}]}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val hits = t.use {
            runBlocking { it.query("docs", SearchRequest(query = QueryInterface.Vector(listOf(0.1f, 0.2f)), limit = 2)) }
        }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/points/query", captured.url.encodedPath)
        assertEquals(2, hits.size)
        assertEquals(PointId.num(1), hits[0].id)
        assertEquals(0.9f, hits[0].score)
        assertEquals(PointId.uuid("u"), hits[1].id)
    }

    @Test
    fun `scroll flow follows the cursor across pages`() {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            val body = if (calls == 1) {
                """{"result":{"points":[{"id":1},{"id":2}],"next_page_offset":2}}"""
            } else {
                """{"result":{"points":[{"id":3}],"next_page_offset":null}}"""
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val client = QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))
        val ids = client.use { runBlocking { it.scroll("docs", pageSize = 2).map { r -> r.id }.toList() } }

        assertEquals(listOf(PointId.num(1), PointId.num(2), PointId.num(3)), ids)
        assertEquals(2, calls)
    }

    @Test
    fun `delete by ids posts a points body with the wait flag`() {
        lateinit var captured: HttpRequestData
        val t = transport { request -> captured = request; respond(okBody, HttpStatusCode.OK, jsonHeaders) }
        t.use { runBlocking { it.delete("docs", DeleteSelector.Ids(listOf(PointId.num(1), PointId.uuid("u"))), wait = true) } }

        assertEquals("/collections/docs/points/delete", captured.url.encodedPath)
        assertEquals("true", captured.url.parameters["wait"])
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("points"))
    }

    @Test
    fun `delete by filter posts a filter body`() {
        lateinit var captured: HttpRequestData
        val t = transport { request -> captured = request; respond(okBody, HttpStatusCode.OK, jsonHeaders) }
        t.use { runBlocking { it.delete("docs", DeleteSelector.ByFilter(filter { must { "lang" eq "en" } }), wait = false) } }

        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertTrue(body.containsKey("filter"))
    }

    @Test
    fun `delete by an empty filter is rejected before any request`() {
        var calls = 0
        val engine = MockEngine { _ -> calls++; respond(okBody, HttpStatusCode.OK, jsonHeaders) }
        val client = QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.use { it.delete("docs") { } } }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `delete by a filter of only empty clause blocks is rejected before any request`() {
        // Regression for the data-loss footgun: an empty `must { }` block must not become a match-all.
        var calls = 0
        val engine = MockEngine { _ -> calls++; respond(okBody, HttpStatusCode.OK, jsonHeaders) }
        val client = QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.use { it.delete("docs") { must { } } } }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `searchBatch posts to points-query-batch and maps each result`() {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(
                """{"result":[{"points":[{"id":1,"score":0.9}]},{"points":[{"id":2,"score":0.5}]}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val client = QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))
        val results = client.use {
            runBlocking { it.searchBatch("docs") { search { query(listOf(0.1f)) }; search { query(listOf(0.2f)) } } }
        }

        assertEquals("/collections/docs/points/query/batch", captured.url.encodedPath)
        assertEquals(2, results.size)
        assertEquals(PointId.num(1), results[0][0].id)
        assertEquals(PointId.num(2), results[1][0].id)
    }

    @Test
    fun `searchGroups posts group_by and maps the groups`() {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(
                """{"result":{"groups":[{"id":"en","hits":[{"id":1,"score":0.9}]}]}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val client = QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))
        val groups = client.use {
            runBlocking { it.searchGroups("docs", groupBy = "lang", groupSize = 3) { query(listOf(0.1f)) } }
        }

        assertEquals("/collections/docs/points/query/groups", captured.url.encodedPath)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertEquals("lang", body["group_by"]!!.jsonPrimitive.content)
        assertEquals("3", body["group_size"]!!.jsonPrimitive.content)
        assertEquals(1, groups.size)
        assertEquals("en", groups[0].id.content)
        assertEquals(PointId.num(1), groups[0].hits[0].id)
    }
}
