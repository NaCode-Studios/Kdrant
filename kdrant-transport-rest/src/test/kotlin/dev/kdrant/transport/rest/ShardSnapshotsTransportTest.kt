package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.kdrantConfig
import dev.kdrant.model.SnapshotPriority
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Shard-scope snapshots: the collection surface with a shard id in the path. */
class ShardSnapshotsTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val snapshotBody =
        """{"result":{"name":"docs-shard-0.snapshot","creation_time":"2026-07-31T09:00:00","size":2048},"status":"ok"}"""

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): QdrantClient =
        QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, MockEngine { request -> handler(request) }))

    @Test
    fun `every shard operation addresses its own shard, not the collection`() {
        val seen = mutableListOf<String>()
        val qdrant = client { request ->
            seen += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.url.encodedPath.endsWith("/snapshots") && request.method == HttpMethod.Get ->
                    respond("""{"result":[],"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/snapshots") ->
                    respond(snapshotBody, HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        qdrant.use {
            runBlocking {
                it.createShardSnapshot("docs", 2)
                it.listShardSnapshots("docs", 2)
                it.deleteShardSnapshot("docs", 2, "docs-shard-0.snapshot")
                it.recoverShardSnapshot("docs", 2, location = "file:///s.snapshot")
            }
        }

        assertEquals(
            listOf(
                "POST /collections/docs/shards/2/snapshots",
                "GET /collections/docs/shards/2/snapshots",
                "DELETE /collections/docs/shards/2/snapshots/docs-shard-0.snapshot",
                "PUT /collections/docs/shards/2/snapshots/recover",
            ),
            seen,
        )
    }

    @Test
    fun `create returns the snapshot description the server sent`() {
        val qdrant = client { respond(snapshotBody, HttpStatusCode.OK, jsonHeaders) }

        val snapshot = qdrant.use { runBlocking { it.createShardSnapshot("docs", 0) } }

        assertEquals("docs-shard-0.snapshot", snapshot.name)
        assertEquals(2048L, snapshot.size)
    }

    @Test
    fun `download streams the shard snapshot's bytes`() {
        val qdrant = client { respond("shard-snapshot-bytes", HttpStatusCode.OK) }

        val bytes = qdrant.use {
            runBlocking { it.downloadShardSnapshot("docs", 1, "s.snapshot").toList() }
        }

        assertEquals("shard-snapshot-bytes", bytes.reduce { a, b -> a + b }.decodeToString())
    }

    @Test
    fun `upload posts multipart to the shard's upload path, carrying priority and checksum`() {
        lateinit var captured: HttpRequestData
        val qdrant = client { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use {
            runBlocking {
                it.uploadShardSnapshot(
                    "docs",
                    3,
                    flowOf("a".toByteArray()),
                    priority = SnapshotPriority.SNAPSHOT,
                    checksum = "abc123",
                )
            }
        }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/shards/3/snapshots/upload", captured.url.encodedPath)
        assertEquals("snapshot", captured.url.parameters["priority"])
        assertEquals("abc123", captured.url.parameters["checksum"])
        assertTrue(captured.body.contentType?.contentSubtype?.startsWith("form-data") == true)
    }

    @Test
    fun `a negative shard id is rejected before a request goes out`() {
        var calls = 0
        val qdrant = client {
            calls++
            respond(snapshotBody, HttpStatusCode.OK, jsonHeaders)
        }

        assertThrows(IllegalArgumentException::class.java) {
            qdrant.use { runBlocking { it.createShardSnapshot("docs", -1) } }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `a collection name needing encoding is encoded, and the shard id is not`() {
        lateinit var captured: HttpRequestData
        val qdrant = client { request ->
            captured = request
            respond(snapshotBody, HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use { runBlocking { it.createShardSnapshot("my docs", 7) } }

        assertEquals("/collections/my%20docs/shards/7/snapshots", captured.url.encodedPath)
    }
}
