package dev.kdrant.springai

import dev.kdrant.QdrantClient
import dev.kdrant.model.PointId
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter

/**
 * A Spring AI [VectorStore] backed by [Kdrant][QdrantClient] — use Qdrant from Spring AI over Kdrant's
 * small pure-Kotlin REST transport instead of the gRPC stack.
 *
 * Documents are embedded with the supplied [embeddingModel] and stored as points in [collectionName];
 * the document text is kept under the `doc_content` payload key and the rest of the metadata alongside it.
 * The collection must already exist with a vector size matching the embedding model.
 *
 * Metadata-filter expressions are not yet supported (they throw); everything else maps straight through.
 */
public class KdrantVectorStore(
    private val client: QdrantClient,
    private val embeddingModel: EmbeddingModel,
    private val collectionName: String,
) : VectorStore {

    override fun add(documents: List<Document>) {
        if (documents.isEmpty()) return
        val embeddings = embeddingModel.embed(documents.map { it.text ?: "" })
        runBlocking {
            client.upsert(collectionName) {
                documents.forEachIndexed { index, document ->
                    point(document.id) {
                        vector(*embeddings[index])
                        payload {
                            document.metadata.forEach { (key, value) -> putAny(key, value) }
                            putAny(CONTENT_KEY, document.text ?: "")
                        }
                    }
                }
            }
        }
    }

    override fun delete(idList: List<String>) {
        if (idList.isEmpty()) return
        runBlocking { client.delete(collectionName, idList.map { PointId.uuid(it) }) }
    }

    override fun delete(filterExpression: Filter.Expression) {
        throw UnsupportedOperationException(
            "kdrant-spring-ai does not yet support delete by a metadata filter expression",
        )
    }

    override fun similaritySearch(request: SearchRequest): List<Document> {
        if (request.hasFilterExpression()) {
            throw UnsupportedOperationException(
                "kdrant-spring-ai does not yet support metadata filter expressions in similaritySearch",
            )
        }
        val queryVector = embeddingModel.embed(request.query)
        val hits = runBlocking {
            client.search(collectionName) {
                query(*queryVector)
                limit = request.topK
                if (request.similarityThreshold > 0.0) scoreThreshold = request.similarityThreshold
                withPayload = WithPayload.All
            }
        }
        return hits.map { it.toDocument() }
    }

    private fun ScoredPoint.toDocument(): Document {
        val fields: JsonObject = payload ?: JsonObject(emptyMap())
        val content = (fields[CONTENT_KEY] as? JsonPrimitive)?.contentOrNull ?: ""
        val metadata = fields
            .filterKeys { it != CONTENT_KEY }
            .mapValues { (_, element) -> element.toPlainValue() }
        return Document.builder()
            .id(id.asString())
            .text(content)
            .metadata(metadata)
            .score(score.toDouble())
            .build()
    }

    private companion object {
        const val CONTENT_KEY = "doc_content"
    }
}

private fun PointId.asString(): String = when (this) {
    is PointId.Num -> value.toString()
    is PointId.Uuid -> value
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

private fun JsonElement.toPlainValue(): Any = when (this) {
    is JsonPrimitive -> when {
        this is kotlinx.serialization.json.JsonNull -> ""
        isString -> content
        booleanOrNull != null -> booleanOrNull!!
        longOrNull != null -> longOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> content
    }
    else -> toString()
}
