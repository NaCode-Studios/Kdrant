@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.kdrantConfig
import dev.kdrant.model.AliasOperation
import dev.kdrant.model.FacetValue
import dev.kdrant.model.PointId
import dev.kdrant.model.SearchMatrixRequest
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Wire tests (Ktor MockEngine) for the M19 aliases, service/health, and analytics endpoints. */
class AliasesServiceAnalyticsTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val textHeaders = headersOf(HttpHeaders.ContentType, "text/plain")

    // maxRetries = 0 so the not-ready (503) probe test returns immediately instead of backing off.
    private fun transport(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RestQdrantTransport(kdrantConfig("h", 6333) { maxRetries = 0 }, MockEngine { request -> handler(request) })

    private fun bodyOf(request: HttpRequestData) =
        KdrantJson.parseToJsonElement((request.body as TextContent).text).jsonObject

    // --- Aliases ---

    @Test
    fun `updateAliases POSTs an atomic actions batch`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use {
            runBlocking {
                it.updateAliases(
                    listOf(
                        AliasOperation.Create(collectionName = "docs-v2", aliasName = "docs"),
                        AliasOperation.Delete(aliasName = "old"),
                        AliasOperation.Rename(oldAliasName = "x", newAliasName = "y"),
                    ),
                    timeout = null,
                )
            }
        }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/aliases", captured.url.encodedPath)
        val actions = bodyOf(captured)["actions"]!!.jsonArray
        assertEquals(3, actions.size)
        val create = actions[0].jsonObject["create_alias"]!!.jsonObject
        assertEquals("docs-v2", create["collection_name"]!!.jsonPrimitive.content)
        assertEquals("docs", create["alias_name"]!!.jsonPrimitive.content)
        assertEquals("old", actions[1].jsonObject["delete_alias"]!!.jsonObject["alias_name"]!!.jsonPrimitive.content)
        val rename = actions[2].jsonObject["rename_alias"]!!.jsonObject
        assertEquals("x", rename["old_alias_name"]!!.jsonPrimitive.content)
        assertEquals("y", rename["new_alias_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `updateAliases passes the timeout query parameter`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use { runBlocking { it.updateAliases(listOf(AliasOperation.Delete("a")), timeout = 30) } }

        assertEquals("30", captured.url.parameters["timeout"])
    }

    @Test
    fun `listAliases reads all alias descriptions`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":{"aliases":[{"alias_name":"a","collection_name":"c"}]},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val aliases = t.use { runBlocking { it.listAliases() } }

        assertEquals(HttpMethod.Get, captured.method)
        assertEquals("/aliases", captured.url.encodedPath)
        assertEquals(1, aliases.size)
        assertEquals("a", aliases[0].aliasName)
        assertEquals("c", aliases[0].collectionName)
    }

    @Test
    fun `listCollectionAliases targets the collection path`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":{"aliases":[]},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val aliases = t.use { runBlocking { it.listCollectionAliases("docs") } }

        assertEquals("/collections/docs/aliases", captured.url.encodedPath)
        assertTrue(aliases.isEmpty())
    }

    // --- Service & health ---

    @Test
    fun `healthz is true on 2xx and false on a not-ready status`() {
        val ok = transport { respond("healthz check passed", HttpStatusCode.OK, textHeaders) }
        assertTrue(ok.use { runBlocking { it.healthz() } })

        val down = transport { respond("", HttpStatusCode.ServiceUnavailable) }
        assertFalse(down.use { runBlocking { it.readyz() } })
    }

    @Test
    fun `livez GETs the probe path`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("passed", HttpStatusCode.OK, textHeaders)
        }
        assertTrue(t.use { runBlocking { it.livez() } })
        assertEquals("/livez", captured.url.encodedPath)
    }

    @Test
    fun `listCollections reads the collection names`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":{"collections":[{"name":"a"},{"name":"b"}]},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val names = t.use { runBlocking { it.listCollections() } }

        assertEquals("/collections", captured.url.encodedPath)
        assertEquals(listOf("a", "b"), names.map { it.name })
    }

    @Test
    fun `telemetry unwraps the result object`() {
        val t = transport {
            respond("""{"result":{"collections":{"number_of_collections":3}},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val telemetry = t.use { runBlocking { it.telemetry() } }
        assertTrue(telemetry.containsKey("collections"))
    }

    @Test
    fun `metrics returns the raw text body`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("# HELP app_info\napp_info 1", HttpStatusCode.OK, textHeaders)
        }
        val metrics = t.use { runBlocking { it.metrics() } }

        assertEquals("/metrics", captured.url.encodedPath)
        assertTrue(metrics.contains("app_info"))
    }

    @Test
    fun `listIssues unwraps result and clearIssues DELETEs`() {
        val list = transport { respond("""{"result":{"issues":[]},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders) }
        val issues = list.use { runBlocking { it.listIssues() } }
        assertTrue(issues.jsonObject.containsKey("issues"))

        lateinit var captured: HttpRequestData
        val clear = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        clear.use { runBlocking { it.clearIssues() } }
        assertEquals(HttpMethod.Delete, captured.method)
        assertEquals("/issues", captured.url.encodedPath)
    }

    // --- Analytics ---

    @Test
    fun `facet POSTs key and reads mixed-typed value hits`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":{"hits":[{"value":"en","count":42},{"value":10,"count":3},{"value":true,"count":1}]},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val hits = t.use { runBlocking { it.facet("docs", key = "lang", filter = null, limit = 20, exact = true) } }

        assertEquals("/collections/docs/facet", captured.url.encodedPath)
        val body = bodyOf(captured)
        assertEquals("lang", body["key"]!!.jsonPrimitive.content)
        assertEquals("20", body["limit"]!!.jsonPrimitive.content)
        assertEquals("true", body["exact"]!!.jsonPrimitive.content)
        assertEquals(FacetValue.StringValue("en"), hits[0].value)
        assertEquals(42L, hits[0].count)
        assertEquals(FacetValue.IntValue(10), hits[1].value)
        assertEquals(FacetValue.BoolValue(true), hits[2].value)
    }

    @Test
    fun `facet omits exact when false`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":{"hits":[]},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use { runBlocking { it.facet("docs", key = "lang", filter = null, limit = null, exact = false) } }
        assertNull(bodyOf(captured)["exact"])
    }

    @Test
    fun `searchMatrixPairs posts the request and decodes scored point pairs`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":{"pairs":[{"a":1,"b":2,"score":0.9},{"a":"u-1","b":3,"score":0.5}]},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val pairs = t.use { runBlocking { it.searchMatrixPairs("docs", SearchMatrixRequest(sample = 100, limit = 5)) } }

        assertEquals("/collections/docs/points/search/matrix/pairs", captured.url.encodedPath)
        val body = bodyOf(captured)
        assertEquals("100", body["sample"]!!.jsonPrimitive.content)
        assertEquals("5", body["limit"]!!.jsonPrimitive.content)
        assertEquals(2, pairs.pairs.size)
        assertEquals(PointId.Num(1u), pairs.pairs[0].a)
        assertEquals(PointId.Num(2u), pairs.pairs[0].b)
        assertEquals(0.9f, pairs.pairs[0].score)
        assertEquals(PointId.Uuid("u-1"), pairs.pairs[1].a)
    }

    @Test
    fun `searchMatrixOffsets decodes the COO matrix`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(
                """{"result":{"offsets_row":[0,1],"offsets_col":[1,2],"scores":[0.9,0.5],"ids":[1,2,3]},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val m = t.use { runBlocking { it.searchMatrixOffsets("docs", SearchMatrixRequest()) } }

        assertEquals("/collections/docs/points/search/matrix/offsets", captured.url.encodedPath)
        assertEquals(listOf(0L, 1L), m.offsetsRow)
        assertEquals(listOf(1L, 2L), m.offsetsCol)
        assertEquals(listOf(0.9f, 0.5f), m.scores)
        assertEquals(listOf(PointId.Num(1u), PointId.Num(2u), PointId.Num(3u)), m.ids)
    }
}
