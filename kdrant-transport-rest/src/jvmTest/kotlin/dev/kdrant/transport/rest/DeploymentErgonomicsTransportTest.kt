@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.payloadOf
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.kdrantConfig
import dev.kdrant.model.Direction
import dev.kdrant.model.Distance
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M27: `ensureCollection`, an ordered `scroll` that can resume, and `batchUpdate`. */
class DeploymentErgonomicsTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val okBody = """{"result":true,"status":"ok"}"""

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): QdrantClient =
        QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, MockEngine { request -> handler(request) }))

    private fun HttpRequestData.bodyJson() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    /** A `getCollection` response carrying one anonymous vector of the given geometry. */
    private fun collectionInfo(size: Int, distance: String) = """
        {"result":{"status":"green","points_count":3,"segments_count":1,
         "config":{"params":{"vectors":{"size":$size,"distance":"$distance"},"on_disk_payload":true}},
         "payload_schema":{"lang":{"data_type":"keyword","points":3}}},
         "status":"ok"}
    """.trimIndent()

    // --- ensureCollection ------------------------------------------------------------------------

    @Test
    fun `ensureCollection creates the collection when it is missing`() {
        val paths = mutableListOf<String>()
        val qdrant = client { request ->
            paths += "${request.method.value} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/collections/docs/exists" -> respond("""{"result":{"exists":false}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond(okBody, HttpStatusCode.OK, jsonHeaders)
            }
        }

        val created = qdrant.use {
            runBlocking { it.ensureCollection("docs") { vector { size = 4; distance = Distance.COSINE } } }
        }

        assertTrue(created)
        assertEquals(listOf("GET /collections/docs/exists", "PUT /collections/docs"), paths)
    }

    @Test
    fun `ensureCollection accepts an existing collection with the requested vectors`() {
        val qdrant = client { request ->
            when (request.url.encodedPath) {
                "/collections/docs/exists" -> respond("""{"result":{"exists":true}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond(collectionInfo(size = 4, distance = "Cosine"), HttpStatusCode.OK, jsonHeaders)
            }
        }

        val created = qdrant.use {
            runBlocking { it.ensureCollection("docs") { vector { size = 4; distance = Distance.COSINE } } }
        }

        assertFalse(created)
    }

    @Test
    fun `ensureCollection reports the vector geometry that does not match`() {
        val qdrant = client { request ->
            when (request.url.encodedPath) {
                "/collections/docs/exists" -> respond("""{"result":{"exists":true}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond(collectionInfo(size = 768, distance = "Dot"), HttpStatusCode.OK, jsonHeaders)
            }
        }

        val error = assertThrows(IllegalStateException::class.java) {
            qdrant.use { runBlocking { it.ensureCollection("docs") { vector { size = 4; distance = Distance.COSINE } } } }
        }

        assertTrue(error.message!!.contains("has size 768, expected 4"), error.message)
    }

    @Test
    fun `ensureCollection absorbs a collection created by someone else in the meantime`() {
        var existsCalls = 0
        val qdrant = client { request ->
            when (request.url.encodedPath) {
                "/collections/docs/exists" -> {
                    existsCalls++
                    respond("""{"result":{"exists":false}}""", HttpStatusCode.OK, jsonHeaders)
                }
                // The create loses the race and the server rejects it as already existing.
                else -> if (request.method == HttpMethod.Put) {
                    respond("""{"status":{"error":"already exists"}}""", HttpStatusCode.Conflict, jsonHeaders)
                } else {
                    respond(collectionInfo(size = 4, distance = "Cosine"), HttpStatusCode.OK, jsonHeaders)
                }
            }
        }

        val created = qdrant.use {
            runBlocking { it.ensureCollection("docs") { vector { size = 4; distance = Distance.COSINE } } }
        }

        assertFalse(created)
        assertEquals(1, existsCalls)
    }

    // --- ordered scroll --------------------------------------------------------------------------

    @Test
    fun `an ordered scroll sends order_by and no offset`() {
        lateinit var captured: HttpRequestData
        val qdrant = client { request ->
            captured = request
            respond("""{"result":{"points":[{"id":1,"order_value":10}],"next_page_offset":null}}""", HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use {
            runBlocking { it.scroll("docs", pageSize = 8) { orderBy("ts", Direction.DESC, startFrom = 99) }.toList() }
        }

        val body = captured.bodyJson()
        val orderBy = body["order_by"]!!.jsonObject
        assertEquals("ts", orderBy["key"]!!.jsonPrimitive.content)
        assertEquals("desc", orderBy["direction"]!!.jsonPrimitive.content)
        assertEquals(99, orderBy["start_from"]!!.jsonPrimitive.content.toInt())
        assertNull(body["offset"])
    }

    @Test
    fun `an ordered scroll resumes from the order value and emits each point once`() {
        val starts = mutableListOf<String?>()
        var page = 0
        val qdrant = client { request ->
            starts += request.bodyJson()["order_by"]!!.jsonObject["start_from"]?.jsonPrimitive?.content
            page++
            when (page) {
                // Full page; ids 2 and 3 share the boundary value 20.
                1 -> respond(
                    """{"result":{"points":[{"id":1,"order_value":10},{"id":2,"order_value":20},{"id":3,"order_value":20}]}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                // start_from is inclusive, so the server repeats ids 2 and 3 before the new point.
                2 -> respond(
                    """{"result":{"points":[{"id":2,"order_value":20},{"id":3,"order_value":20},{"id":4,"order_value":30}]}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                // Short page: only the repeated boundary point is left, so the scroll ends here.
                else -> respond(
                    """{"result":{"points":[{"id":4,"order_value":30}]}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
        }

        val ids = qdrant.use {
            runBlocking { it.scroll("docs", pageSize = 3) { orderBy("ts") }.toList().map { record -> record.id } }
        }

        assertEquals(listOf(PointId.num(1), PointId.num(2), PointId.num(3), PointId.num(4)), ids)
        assertEquals(listOf(null, "20", "30"), starts)
    }

    @Test
    fun `an ordered scroll that cannot advance fails instead of looping`() {
        val qdrant = client {
            respond(
                """{"result":{"points":[{"id":1,"order_value":5},{"id":2,"order_value":5}]}}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val error = assertThrows(IllegalStateException::class.java) {
            qdrant.use { runBlocking { it.scroll("docs", pageSize = 2) { orderBy("ts") }.toList() } }
        }

        assertTrue(error.message!!.contains("cannot advance"), error.message)
    }

    @Test
    fun `an unordered scroll still pages through the id cursor`() {
        var page = 0
        val qdrant = client {
            page++
            if (page == 1) {
                respond("""{"result":{"points":[{"id":1}],"next_page_offset":2}}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"result":{"points":[{"id":2}],"next_page_offset":null}}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val ids = qdrant.use { runBlocking { it.scroll("docs", pageSize = 1).toList().map { record -> record.id } } }

        assertEquals(listOf(PointId.num(1), PointId.num(2)), ids)
    }

    // --- batchUpdate -----------------------------------------------------------------------------

    @Test
    fun `batchUpdate posts the operations in the order they were added`() {
        lateinit var captured: HttpRequestData
        val qdrant = client { request ->
            captured = request
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use {
            runBlocking {
                it.batchUpdate("docs", wait = true) {
                    upsert { point(1) { vector(0.1f, 0.2f) } }
                    setPayload(payloadOf("reviewed" to true), byId(1L))
                    deletePayload(listOf("draft"), byId(1L))
                    clearPayload(byFilter { must { "stale" eq true } })
                    delete(byFilter { must { "lang" eq "xx" } })
                }
            }
        }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/points/batch", captured.url.encodedPath)
        assertEquals("true", captured.url.parameters["wait"])

        val operations = captured.bodyJson()["operations"]!!.jsonArray
        assertEquals(
            listOf("upsert", "set_payload", "delete_payload", "clear_payload", "delete"),
            operations.map { it.jsonObject.keys.single() },
        )
        assertEquals(
            true,
            operations[1].jsonObject["set_payload"]!!.jsonObject["payload"]!!
                .jsonObject["reviewed"]!!.jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            1,
            operations[1].jsonObject["set_payload"]!!.jsonObject["points"]!!.jsonArray.size,
        )
    }

    @Test
    fun `batchUpdate rejects a filter selector with no conditions`() {
        val qdrant = client { respond(okBody, HttpStatusCode.OK, jsonHeaders) }

        assertThrows(IllegalArgumentException::class.java) {
            qdrant.use { runBlocking { it.batchUpdate("docs") { delete(byFilter { }) } } }
        }
    }
}
