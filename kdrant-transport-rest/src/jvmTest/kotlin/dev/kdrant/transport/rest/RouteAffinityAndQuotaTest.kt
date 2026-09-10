@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.kdrantConfig
import dev.kdrant.model.PointId
import dev.kdrant.model.QuotaConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-affinity hint travels as a header rather than in the body, which is the thing a test has to
 * pin: a token that quietly ends up serialized into the request would be rejected by the server as an
 * unknown field, and one that quietly goes nowhere leaves the caller with the stale reads they were
 * trying to avoid, and no signal either way.
 */
class RouteAffinityAndQuotaTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun clientRecording(
        body: String = """{"result":{"points":[]},"status":"ok"}""",
        record: (HttpRequestData) -> Unit,
    ): QdrantClient = QdrantClient(
        RestQdrantTransport(
            kdrantConfig("h", 6333) {},
            MockEngine { request -> record(request); respond(body, HttpStatusCode.OK, jsonHeaders) },
        ),
    )

    private fun sentBody(request: HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()

    @Test
    fun `a search carrying a route affinity sends it as a header and not in the body`() {
        lateinit var captured: HttpRequestData
        clientRecording(record = { captured = it }).use { c ->
            runBlocking { c.search("docs") { query(0.1f, 0.2f); routeAffinity = "user-42" } }
        }

        assertEquals("user-42", captured.headers["X-Qdrant-Route-Affinity"])
        assertTrue("user-42" !in sentBody(captured), "the token reached the body: ${sentBody(captured)}")
    }

    @Test
    fun `a search that names no affinity sends no header`() {
        lateinit var captured: HttpRequestData
        clientRecording(record = { captured = it }).use { c ->
            runBlocking { c.search("docs") { query(0.1f, 0.2f) } }
        }

        assertNull(captured.headers["X-Qdrant-Route-Affinity"])
    }

    @Test
    fun `scroll, count and retrieve each carry the token`() {
        val seen = mutableListOf<String?>()
        val bodies = mapOf(
            "scroll" to """{"result":{"points":[],"next_page_offset":null},"status":"ok"}""",
            "count" to """{"result":{"count":0},"status":"ok"}""",
            "points" to """{"result":[],"status":"ok"}""",
        )
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) {},
            MockEngine { request ->
                seen += request.headers["X-Qdrant-Route-Affinity"]
                val key = request.url.encodedPath.substringAfterLast('/')
                respond(bodies.getValue(key), HttpStatusCode.OK, jsonHeaders)
            },
        )
        QdrantClient(transport).use { c ->
            runBlocking {
                c.scroll("docs", pageSize = 2) { routeAffinity = "session-7" }.toList()
                c.count("docs", routeAffinity = "session-7")
                c.retrieve("docs", listOf(PointId.num(1)), routeAffinity = "session-7")
            }
        }

        assertEquals(listOf("session-7", "session-7", "session-7"), seen)
    }

    /**
     * A batch is one HTTP request, so it can be pinned to one replica. Honouring the first token and
     * dropping the rest would be a stale read the caller has no way to explain, so the engine refuses
     * instead.
     */
    @Test
    fun `a batch whose searches ask for different replicas is refused rather than half-honoured`() {
        clientRecording(body = """{"result":[],"status":"ok"}""", record = {}).use { c ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    c.searchBatch("docs") {
                        search { query(0.1f); routeAffinity = "user-1" }
                        search { query(0.2f); routeAffinity = "user-2" }
                    }
                }
            }
            assertTrue("user-1" in failure.message.orEmpty(), failure.message.orEmpty())
        }
    }

    @Test
    fun `a batch whose searches agree sends the token once`() {
        lateinit var captured: HttpRequestData
        clientRecording(body = """{"result":[],"status":"ok"}""", record = { captured = it }).use { c ->
            runBlocking {
                c.searchBatch("docs") {
                    search { query(0.1f); routeAffinity = "user-1" }
                    search { query(0.2f); routeAffinity = "user-1" }
                }
            }
        }

        assertEquals("user-1", captured.headers["X-Qdrant-Route-Affinity"])
    }

    // --- Quotas ----------------------------------------------------------------------------------

    @Test
    fun `reading the quota returns the config in force and this node's utilization`() {
        val body = """
            {"result":{
              "config":{"enabled":true,"max_resident_memory_percent":90,"release_margin_percent":5},
              "usage":{"resident_memory_percent":41,"disk_usage_percent":12},
              "peers":{"1":{"exceeded":{"resident_memory":false,"disk_usage":null},
                            "resident_memory_percent":41,"disk_usage_percent":12}}
            },"status":"ok"}
        """.trimIndent()
        val status = QdrantClient(
            RestQdrantTransport(
                kdrantConfig("h", 6333) {},
                MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) },
            ),
        ).use { runBlocking { it.quotas() } }

        assertEquals(true, status.config.enabled)
        assertEquals(90, status.config.maxResidentMemoryPercent)
        assertEquals(41, status.usage.residentMemoryPercent)
        val peer = status.peers?.getValue("1")
        assertEquals(false, peer?.exceeded?.residentMemory)
        assertNull(peer?.exceeded?.diskUsage, "a resource that is not enforced is null, not false")
        assertEquals(false, peer?.exceeded?.any)
    }

    @Test
    fun `updating the quota PUTs the config and unset limits are omitted rather than zeroed`() {
        lateinit var captured: HttpRequestData
        val body = """{"result":{"config":{"enabled":true},"usage":{}},"status":"ok"}"""
        QdrantClient(
            RestQdrantTransport(
                kdrantConfig("h", 6333) {},
                MockEngine { request -> captured = request; respond(body, HttpStatusCode.OK, jsonHeaders) },
            ),
        ).use { runBlocking { it.updateQuotas(QuotaConfig(enabled = true, maxDiskUsagePercent = 85)) } }

        assertEquals("PUT", captured.method.value)
        assertEquals("/quotas", captured.url.encodedPath)
        assertEquals("""{"enabled":true,"max_disk_usage_percent":85}""", sentBody(captured))
    }

    @Test
    fun `a quota percentage outside the range Qdrant takes is refused before it is sent`() {
        assertThrows(IllegalArgumentException::class.java) { QuotaConfig(maxDiskUsagePercent = 0) }
        assertThrows(IllegalArgumentException::class.java) { QuotaConfig(maxResidentMemoryPercent = 101) }
        assertThrows(IllegalArgumentException::class.java) { QuotaConfig(releaseMarginPercent = 101) }
        // Zero is a valid margin: release as soon as usage is back under the limit.
        QuotaConfig(releaseMarginPercent = 0)
    }
}
