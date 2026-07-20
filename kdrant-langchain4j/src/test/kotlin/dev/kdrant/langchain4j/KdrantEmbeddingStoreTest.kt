package dev.kdrant.langchain4j

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.model.PointId
import dev.kdrant.model.ScoredPoint
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.filter.Filter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KdrantEmbeddingStoreTest {

    private val client = mockk<QdrantClient>(relaxed = true)
    private val store = KdrantEmbeddingStore(client, "docs")

    @Test
    fun `search maps scored points to embedding matches`() {
        coEvery { client.search(any(), any()) } returns listOf(
            ScoredPoint(
                id = PointId.uuid("seg-1"),
                score = 0.91f,
                payload = buildJsonObject {
                    put("text", "hello")
                    put("lang", "en")
                },
            ),
        )
        val request = EmbeddingSearchRequest.builder()
            .queryEmbedding(Embedding.from(floatArrayOf(0.1f, 0.2f)))
            .maxResults(5)
            .build()

        val result = store.search(request)

        assertEquals(1, result.matches().size)
        val match = result.matches()[0]
        assertEquals("seg-1", match.embeddingId())
        assertEquals(0.91, match.score(), 1e-6)
        assertEquals("hello", match.embedded().text())
        assertEquals("en", match.embedded().metadata().toMap()["lang"])
    }

    @Test
    fun `add stores the embedding and returns an id`() {
        val id = store.add(Embedding.from(floatArrayOf(0.1f, 0.2f)), TextSegment.from("hi"))

        assertNotNull(id)
        coVerify { client.upsert(any<String>(), any<Boolean>(), any<UpsertBuilder.() -> Unit>()) }
    }

    @Test
    fun `search with a metadata filter is unsupported`() {
        val filter = mockk<Filter>(relaxed = true)
        val request = EmbeddingSearchRequest.builder()
            .queryEmbedding(Embedding.from(floatArrayOf(0.1f)))
            .filter(filter)
            .build()

        assertThrows(UnsupportedOperationException::class.java) { store.search(request) }
    }
}
