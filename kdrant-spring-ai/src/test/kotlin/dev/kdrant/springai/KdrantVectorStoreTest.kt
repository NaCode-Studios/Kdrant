package dev.kdrant.springai

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.model.Condition
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.FieldMatcher
import dev.kdrant.model.PointId
import dev.kdrant.model.ScoredPoint
import dev.kdrant.transport.QdrantTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.filter.Filter
import dev.kdrant.model.Filter as KdrantFilter
import dev.kdrant.model.SearchRequest as KdrantSearchRequest

class KdrantVectorStoreTest {

    private val embeddingModel = mockk<EmbeddingModel>()
    private val client = mockk<QdrantClient>(relaxed = true)
    private val store = KdrantVectorStore(client, embeddingModel, "docs")

    @Test
    fun `similaritySearch maps scored points to documents`() {
        every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.1f, 0.2f)
        coEvery { client.search(any(), any()) } returns listOf(
            ScoredPoint(
                id = PointId.uuid("doc-1"),
                score = 0.87f,
                payload = buildJsonObject {
                    put("doc_content", "hello world")
                    put("lang", "en")
                },
            ),
        )

        val docs = store.similaritySearch(SearchRequest.builder().query("hi").topK(5).build())

        assertEquals(1, docs.size)
        assertEquals("doc-1", docs[0].id)
        assertEquals("hello world", docs[0].text)
        assertEquals("en", docs[0].metadata["lang"])
        assertEquals(0.87, docs[0].score!!, 1e-6)
    }

    @Test
    fun `add embeds the documents and upserts them`() {
        every { embeddingModel.embed(any<List<String>>()) } returns listOf(floatArrayOf(0.1f, 0.2f))

        store.add(listOf(Document("some text")))

        coVerify { client.upsert(any<String>(), any<Boolean>(), any<UpsertBuilder.() -> Unit>()) }
    }

    @Test
    fun `delete by a filter expression translates it and deletes by filter`() {
        val transport = mockk<QdrantTransport>()
        val selector = slot<DeleteSelector>()
        coEvery { transport.delete(eq("docs"), capture(selector), any()) } returns Unit

        KdrantVectorStore(QdrantClient(transport), embeddingModel, "docs").delete(langIsEnglish)

        assertEquals(DeleteSelector.ByFilter(translatedLangFilter), selector.captured)
    }

    @Test
    fun `similaritySearch carries the translated filter into the wire request`() {
        every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.1f, 0.2f)
        val transport = mockk<QdrantTransport>()
        val request = slot<KdrantSearchRequest>()
        coEvery { transport.query(eq("docs"), capture(request)) } returns emptyList()
        val store = KdrantVectorStore(QdrantClient(transport), embeddingModel, "docs")

        store.similaritySearch(SearchRequest.builder().query("hi").filterExpression(langIsEnglish).build())

        assertEquals(translatedLangFilter, request.captured.filter)
    }

    private companion object {
        val langIsEnglish = Filter.Expression(Filter.ExpressionType.EQ, Filter.Key("lang"), Filter.Value("en"))
        val translatedLangFilter =
            KdrantFilter(must = listOf(Condition.Field("lang", FieldMatcher.Match(JsonPrimitive("en")))))
    }
}
