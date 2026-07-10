@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.kdrantConfig
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.VectorData
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpsertTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val okBody = """{"result":true,"status":"ok"}"""

    private fun points(n: Int): List<PointStruct> =
        List(n) { PointStruct(PointId.num(it.toULong() + 1u), VectorData.Dense(listOf(0.5f))) }

    private fun bodyPointCount(request: HttpRequestData): Int =
        KdrantJson.decodeFromString(UpsertRequest.serializer(), (request.body as TextContent).text).points.size

    @Test
    fun `upsert issues PUT to the points path with wait param and body`() {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }
        val transport = RestQdrantTransport(kdrantConfig("h", 6333) {}, engine)
        transport.use { runBlocking { it.upsert("docs", points(3), wait = true) } }

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/collections/docs/points", captured.url.encodedPath)
        assertEquals("true", captured.url.parameters["wait"])
        assertEquals(3, bodyPointCount(captured))
    }

    @Test
    fun `upsert splits into batches under the configured size`() {
        val batchSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            batchSizes += bodyPointCount(request)
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }
        val transport = RestQdrantTransport(kdrantConfig("h", 6333) {}, engine, upsertBatchSize = 2)
        transport.use { runBlocking { it.upsert("docs", points(5), wait = false) } }

        assertEquals(listOf(2, 2, 1), batchSizes)
    }

    @Test
    fun `a non-positive upsertBatchSize is rejected`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            RestQdrantTransport(kdrantConfig("h", 6333) {}, upsertBatchSize = 0)
        }
    }

    @Test
    fun `empty upsert issues no request`() {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }
        val transport = RestQdrantTransport(kdrantConfig("h", 6333) {}, engine)
        transport.use { runBlocking { it.upsert("docs", emptyList(), wait = false) } }

        assertEquals(0, calls)
    }
}
