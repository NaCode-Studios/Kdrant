@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Wire tests (Ktor MockEngine) for the M20 snapshot endpoints, including the streaming download/upload. */
class SnapshotsTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun transport(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RestQdrantTransport(kdrantConfig("h", 6333) { maxRetries = 0 }, MockEngine { request -> handler(request) })

    private val snapshotJson =
        """{"name":"docs-2024.snapshot","creation_time":"2024-07-22T08:31:55","size":1000,"checksum":"ab12"}"""

    @Test
    fun `createSnapshot POSTs with the wait flag and decodes the description`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":$snapshotJson,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val snap = t.use { runBlocking { it.createSnapshot("docs", wait = true) } }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/snapshots", captured.url.encodedPath)
        assertEquals("true", captured.url.parameters["wait"])
        assertEquals("docs-2024.snapshot", snap.name)
        assertEquals(1000L, snap.size)
        assertEquals("ab12", snap.checksum)
    }

    @Test
    fun `listSnapshots reads the description list`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":[$snapshotJson],"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val snaps = t.use { runBlocking { it.listSnapshots("docs") } }

        assertEquals(HttpMethod.Get, captured.method)
        assertEquals("/collections/docs/snapshots", captured.url.encodedPath)
        assertEquals(1, snaps.size)
        assertEquals("docs-2024.snapshot", snaps[0].name)
    }

    @Test
    fun `deleteSnapshot DELETEs the snapshot path`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use { runBlocking { it.deleteSnapshot("docs", "docs-2024.snapshot", wait = false) } }

        assertEquals(HttpMethod.Delete, captured.method)
        assertEquals("/collections/docs/snapshots/docs-2024.snapshot", captured.url.encodedPath)
        assertEquals("false", captured.url.parameters["wait"])
    }

    @Test
    fun `recoverSnapshot PUTs location priority and checksum`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        t.use {
            runBlocking {
                it.recoverSnapshot(
                    "docs",
                    location = "http://peer:6333/collections/docs/snapshots/docs-2024.snapshot",
                    priority = SnapshotPriority.SNAPSHOT,
                    checksum = "ab12",
                    wait = true,
                )
            }
        }

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/collections/docs/snapshots/recover", captured.url.encodedPath)
        val body = KdrantJson.parseToJsonElement((captured.body as TextContent).text).jsonObject
        assertEquals(
            "http://peer:6333/collections/docs/snapshots/docs-2024.snapshot",
            body["location"]!!.jsonPrimitive.content,
        )
        assertEquals("snapshot", body["priority"]!!.jsonPrimitive.content)
        assertEquals("ab12", body["checksum"]!!.jsonPrimitive.content)
    }

    @Test
    fun `storage snapshot ops target the root snapshots path`() {
        lateinit var created: HttpRequestData
        val create = transport { request ->
            created = request
            respond("""{"result":$snapshotJson,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        create.use { runBlocking { it.createStorageSnapshot(wait = true) } }
        assertEquals(HttpMethod.Post, created.method)
        assertEquals("/snapshots", created.url.encodedPath)

        lateinit var deleted: HttpRequestData
        val del = transport { request ->
            deleted = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        del.use { runBlocking { it.deleteStorageSnapshot("full-2024.snapshot", wait = true) } }
        assertEquals(HttpMethod.Delete, deleted.method)
        assertEquals("/snapshots/full-2024.snapshot", deleted.url.encodedPath)
    }

    @Test
    fun `downloadSnapshot streams the body bytes as a Flow`() {
        val payload = "SNAPSHOT-BINARY-CONTENT-0123456789".toByteArray()
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(payload, HttpStatusCode.OK)
        }
        val chunks = t.use { runBlocking { it.downloadSnapshot("docs", "docs-2024.snapshot").toList() } }
        val assembled = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        assertEquals(HttpMethod.Get, captured.method)
        assertEquals("/collections/docs/snapshots/docs-2024.snapshot", captured.url.encodedPath)
        assertArrayEquals(payload, assembled)
    }

    @Test
    fun `downloadStorageSnapshot streams from the root path`() {
        val payload = "FULL-STORAGE-SNAPSHOT".toByteArray()
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond(payload, HttpStatusCode.OK)
        }
        val assembled = t.use {
            runBlocking { it.downloadStorageSnapshot("full-2024.snapshot").toList() }
        }.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        assertEquals("/snapshots/full-2024.snapshot", captured.url.encodedPath)
        assertArrayEquals(payload, assembled)
    }

    @Test
    fun `uploadSnapshot POSTs a multipart body with priority and checksum params`() {
        lateinit var captured: HttpRequestData
        val t = transport { request ->
            captured = request
            respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val data = flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        t.use {
            runBlocking {
                it.uploadSnapshot("docs", data, priority = SnapshotPriority.REPLICA, checksum = "ab12", wait = true)
            }
        }

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/collections/docs/snapshots/upload", captured.url.encodedPath)
        assertEquals("replica", captured.url.parameters["priority"])
        assertEquals("ab12", captured.url.parameters["checksum"])
        assertTrue(captured.body.contentType.toString().startsWith("multipart/form-data"))
    }

    @Test
    fun `recoverSnapshot rejects a blank location before any request`() {
        val client = QdrantClient(transport { respond("""{"result":true,"status":"ok"}""", HttpStatusCode.OK, jsonHeaders) })
        client.use { c ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { c.recoverSnapshot("docs", location = "   ") } }
        }
    }
}
