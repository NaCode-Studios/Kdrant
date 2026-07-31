package dev.kdrant.micrometer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The plugin is exercised on a bare Ktor client with a `MockEngine`, which is what a Kdrant client
 * installs it on through its `configureClient` seam.
 */
class KdrantMetricsTest {

    private val registry = SimpleMeterRegistry()

    private fun timerTags(): Map<String, String> =
        registry.get("kdrant.requests").timer().id.tags.associate { it.key to it.value }

    @Test
    fun `a successful request is timed and tagged with its route, not its collection`() {
        val client = HttpClient(MockEngine { respond("{}") }) {
            install(KdrantMetrics) {
                registry = this@KdrantMetricsTest.registry
                tags = listOf(Tag.of("cluster", "eu-1"))
            }
        }

        client.use { runBlocking { it.put("http://h:6333/collections/docs") } }

        assertEquals(1, registry.get("kdrant.requests").timer().count())
        assertEquals(
            mapOf(
                "cluster" to "eu-1",
                "operation" to "/collections/{collection}",
                "method" to "PUT",
                "status" to "200",
                "outcome" to "SUCCESS",
            ),
            timerTags(),
        )
    }

    @Test
    fun `a rejected request is recorded with its status and outcome`() {
        val client = HttpClient(MockEngine { respond("{}", io.ktor.http.HttpStatusCode.BadRequest) }) {
            install(KdrantMetrics) { registry = this@KdrantMetricsTest.registry }
        }

        client.use { runBlocking { it.get("http://h:6333/collections/docs/points/count") } }

        assertEquals("/collections/{collection}/points/count", timerTags()["operation"])
        assertEquals("400", timerTags()["status"])
        assertEquals("CLIENT_ERROR", timerTags()["outcome"])
    }

    @Test
    fun `a request that never gets a response is recorded as a failure`() {
        val client = HttpClient(MockEngine { throw IOException("connection reset") }) {
            install(KdrantMetrics) { registry = this@KdrantMetricsTest.registry }
        }

        assertThrows(IOException::class.java) {
            client.use { runBlocking { it.get("http://h:6333/collections/docs") } }
        }

        assertEquals("none", timerTags()["status"])
        assertEquals("FAILURE", timerTags()["outcome"])
    }

    @Test
    fun `installing without a registry fails at install time, not at the first request`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HttpClient(MockEngine { respond("{}") }) { install(KdrantMetrics) }
        }

        assertEquals(true, error.message!!.contains("MeterRegistry"))
    }

    @Test
    fun `route templates keep every caller-chosen name out of the tag`() {
        assertEquals("/collections", operationOf(listOf("collections")))
        assertEquals("/collections/{collection}", operationOf(listOf("collections", "docs")))
        assertEquals(
            "/collections/{collection}/points/query",
            operationOf(listOf("collections", "docs", "points", "query")),
        )
        assertEquals(
            "/collections/{collection}/index/{field}",
            operationOf(listOf("collections", "docs", "index", "lang")),
        )
        assertEquals(
            "/collections/{collection}/snapshots/{snapshot}",
            operationOf(listOf("collections", "docs", "snapshots", "docs-2026-07-31.snapshot")),
        )
        assertEquals("/snapshots/{snapshot}", operationOf(listOf("snapshots", "all-2026.snapshot")))
        assertEquals("/collections/{collection}/index", operationOf(listOf("collections", "docs", "index")))
        // `aliases` under /collections is a route of its own, not the name of a collection.
        assertEquals("/collections/aliases", operationOf(listOf("collections", "aliases")))
        assertEquals("/healthz", operationOf(listOf("healthz")))
        assertEquals("/", operationOf(emptyList()))
    }
}
