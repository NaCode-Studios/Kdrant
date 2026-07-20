package dev.kdrant.langchain4j

import dev.kdrant.QdrantClient
import dev.kdrant.model.PointId
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.WithPayload
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingMatch
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.EmbeddingSearchResult
import dev.langchain4j.store.embedding.EmbeddingStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * A LangChain4j [EmbeddingStore] of [TextSegment]s backed by [Kdrant][QdrantClient] — use Qdrant from
 * LangChain4j over Kdrant's small pure-Kotlin REST transport instead of the gRPC stack.
 *
 * Each embedding is stored as a point in [collectionName]; the segment text is kept under the `text`
 * payload key and its metadata alongside it. The collection must already exist with a matching vector size.
 * Metadata filters on `search` are not yet supported (they throw).
 */
public class KdrantEmbeddingStore(
    private val client: QdrantClient,
    private val collectionName: String,
) : EmbeddingStore<TextSegment> {

    override fun add(embedding: Embedding): String {
        val id = UUID.randomUUID().toString()
        add(id, embedding)
        return id
    }

    override fun add(id: String, embedding: Embedding) {
        addAll(listOf(id), listOf(embedding), null)
    }

    override fun add(embedding: Embedding, embedded: TextSegment): String {
        val id = UUID.randomUUID().toString()
        addAll(listOf(id), listOf(embedding), listOf(embedded))
        return id
    }

    override fun addAll(embeddings: List<Embedding>): List<String> {
        val ids = embeddings.map { UUID.randomUUID().toString() }
        addAll(ids, embeddings, null)
        return ids
    }

    override fun addAll(ids: List<String>, embeddings: List<Embedding>, embedded: List<TextSegment>?) {
        if (embeddings.isEmpty()) return
        runBlocking {
            client.upsert(collectionName) {
                embeddings.forEachIndexed { index, embedding ->
                    point(ids[index]) {
                        vector(*embedding.vector())
                        val segment = embedded?.getOrNull(index)
                        if (segment != null) {
                            payload {
                                putAny(TEXT_KEY, segment.text())
                                segment.metadata().toMap().forEach { (key, value) -> putAny(key, value) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun search(request: EmbeddingSearchRequest): EmbeddingSearchResult<TextSegment> {
        if (request.filter() != null) {
            throw UnsupportedOperationException("kdrant-langchain4j does not yet support metadata filters in search")
        }
        val queryVector = request.queryEmbedding().vector()
        val hits = runBlocking {
            client.search(collectionName) {
                query(*queryVector)
                limit = request.maxResults()
                if (request.minScore() > 0.0) scoreThreshold = request.minScore()
                withPayload = WithPayload.All
            }
        }
        return EmbeddingSearchResult(hits.map { it.toMatch() })
    }

    private fun ScoredPoint.toMatch(): EmbeddingMatch<TextSegment> {
        val fields: JsonObject = payload ?: JsonObject(emptyMap())
        val text = (fields[TEXT_KEY] as? JsonPrimitive)?.contentOrNull
        val segment = text?.let {
            val metaMap = fields
                .filterKeys { key -> key != TEXT_KEY }
                .mapValues { (_, element) -> element.toPlainValue() }
            TextSegment.from(it, Metadata.from(metaMap))
        }
        return EmbeddingMatch(score.toDouble(), id.asString(), null, segment)
    }

    private companion object {
        const val TEXT_KEY = "text"
    }
}

private fun PointId.asString(): String = when (this) {
    is PointId.Num -> value.toString()
    is PointId.Uuid -> value
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (this is JsonNull) null else content

private fun JsonElement.toPlainValue(): Any = when (this) {
    is JsonPrimitive -> when {
        this is JsonNull -> ""
        isString -> content
        booleanOrNull != null -> booleanOrNull!!
        longOrNull != null -> longOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> content
    }
    else -> toString()
}
