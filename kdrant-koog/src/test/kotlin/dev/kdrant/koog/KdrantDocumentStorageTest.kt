package dev.kdrant.koog

import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import dev.kdrant.QdrantClient
import dev.kdrant.model.PointId
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.SearchRequest
import dev.kdrant.transport.QdrantTransport
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KdrantDocumentStorageTest {

    private val transport = mockk<QdrantTransport>(relaxed = true)
    private val client = QdrantClient(transport)

    /** A deterministic stand-in for a real model: length and first character, which is enough to test wiring. */
    private val embedder: suspend (String) -> List<Float> = { text ->
        listOf(text.length.toFloat(), text.firstOrNull()?.code?.toFloat() ?: 0f)
    }

    private fun storage(namespaceKey: String = "namespace") =
        KdrantDocumentStorage(client, "docs", embedder, TextDocuments, namespaceKey)

    @Test
    fun `search asks Qdrant rather than streaming the collection out to rank it here`() {
        val request = slot<SearchRequest>()
        coEvery { transport.query(eq("docs"), capture(request)) } returns emptyList()

        runBlocking { storage().search(SimilaritySearchRequest("hello", limit = 5), namespace = null) }

        // "hello" is 5 characters starting with 'h' (104), so the embedder ran on the query text.
        assertEquals(5, request.captured.limit)
        assertNull(request.captured.filter)
        assertEquals(listOf(5f, 104f), (request.captured.query as dev.kdrant.model.QueryInterface.Vector).values)
    }

    @Test
    fun `a namespace becomes a payload filter instead of a second collection`() {
        val request = slot<SearchRequest>()
        coEvery { transport.query(eq("docs"), capture(request)) } returns emptyList()

        runBlocking { storage().search(SimilaritySearchRequest("hi", limit = 3), namespace = "tenant-a") }

        assertEquals(
            listOf(dev.kdrant.model.Condition.Field("namespace", dev.kdrant.model.FieldMatcher.Match(JsonPrimitive("tenant-a")))),
            request.captured.filter!!.must,
        )
    }

    @Test
    fun `an empty namespace is not a filter on the empty string`() {
        val request = slot<SearchRequest>()
        coEvery { transport.query(eq("docs"), capture(request)) } returns emptyList()

        runBlocking { storage().search(SimilaritySearchRequest("hi", limit = 3), namespace = "") }

        assertNull(request.captured.filter)
    }

    @Test
    fun `offset and minScore reach the request only when they say something`() {
        val request = slot<SearchRequest>()
        coEvery { transport.query(eq("docs"), capture(request)) } returns emptyList()

        runBlocking {
            storage().search(SimilaritySearchRequest("hi", limit = 3, offset = 0, minScore = null), null)
        }
        assertNull(request.captured.offset)
        assertNull(request.captured.scoreThreshold)

        runBlocking {
            storage().search(SimilaritySearchRequest("hi", limit = 3, offset = 10, minScore = 0.4), null)
        }
        assertEquals(10, request.captured.offset)
        assertEquals(0.4, request.captured.scoreThreshold)
    }

    @Test
    fun `a hit becomes a search result carrying the payload as metadata`() {
        coEvery { transport.query(any(), any()) } returns listOf(
            ScoredPoint(
                id = PointId.uuid("doc-1"),
                score = 0.87f,
                payload = buildJsonObject { put("text", JsonPrimitive("the first document")) },
            ),
        )

        val results = runBlocking { storage().search(SimilaritySearchRequest("first", limit = 1), "tenant-a") }

        val result = results.single()
        assertEquals("the first document", result.document)
        assertEquals("doc-1", result.id)
        assertEquals(0.87, result.score.value, 1e-6)
        assertEquals("tenant-a", result.namespace)
        assertEquals("the first document", result.metadata!!["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a filter expression Koog understands and this does not is refused rather than ignored`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                storage().search(
                    SimilaritySearchRequest("hi", limit = 1, filterExpression = "lang == 'en'"),
                    namespace = null,
                )
            }
        }
        assertEquals(true, error.message!!.contains("filterExpression"), error.message)
    }

    @Test
    fun `add returns one generated id per document and update keeps the ids it was given`() {
        val added = runBlocking { storage().add(listOf("one", "two"), namespace = null) }
        assertEquals(2, added.size)
        assertEquals(2, added.toSet().size, "each document needs its own id")

        val updated = runBlocking { storage().update(mapOf("a" to "one", "b" to "two"), namespace = null) }
        assertEquals(listOf("a", "b"), updated)
    }

    @Test
    fun `empty inputs do no work at all`() {
        val empty = storage()

        assertEquals(emptyList<String>(), runBlocking { empty.add(emptyList(), null) })
        assertEquals(emptyList<String>(), runBlocking { empty.update(emptyMap(), null) })
        assertEquals(emptyList<String>(), runBlocking { empty.get(emptyList(), null) })
        assertEquals(emptyList<String>(), runBlocking { empty.delete(emptyList(), null) })
    }

    @Test
    fun `the namespace key is configurable, for a payload that already uses that name`() {
        val request = slot<SearchRequest>()
        coEvery { transport.query(eq("docs"), capture(request)) } returns emptyList()

        runBlocking { storage(namespaceKey = "tenant").search(SimilaritySearchRequest("hi", limit = 1), "acme") }

        assertEquals("tenant", (request.captured.filter!!.must!!.single() as dev.kdrant.model.Condition.Field).key)
    }
}
