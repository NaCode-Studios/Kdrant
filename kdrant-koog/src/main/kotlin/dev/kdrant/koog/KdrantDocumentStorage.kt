package dev.kdrant.koog

import ai.koog.rag.base.storage.DeletionStorage
import ai.koog.rag.base.storage.LookupStorage
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.WriteStorage
import ai.koog.rag.base.storage.search.Score
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SearchResult
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import dev.kdrant.QdrantClient
import dev.kdrant.model.PointId
import dev.kdrant.model.WithPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * A [Koog](https://github.com/JetBrains/koog) document storage backed by [Kdrant][QdrantClient], so a
 * Koog RAG agent can keep its documents in Qdrant.
 *
 * It implements Koog's search-side storage interfaces directly rather than `VectorStorageBackend`.
 * That interface has no search method: Koog's own `EmbeddingStorage` ranks by streaming every stored
 * document out of the backend and scoring in memory, which through a vector database would mean paying
 * for an index and then pulling the whole collection over the network on every query. Here Qdrant runs
 * the search.
 *
 * It also depends only on Koog's `rag-base`, which is stable, rather than on `rag-vector`, which is
 * published as a beta.
 *
 * ```kotlin
 * val storage = KdrantDocumentStorage(qdrant, "docs", embedder = ::embed, codec = TextDocuments)
 * storage.add(listOf("the first document", "the second"))
 * val hits = storage.search(SimilaritySearchRequest("something like the first", limit = 5))
 * ```
 *
 * The collection must already exist with a vector size matching [embedder]; `ensureCollection` is the
 * usual way to arrange that at start-up.
 *
 * @param namespaceKey the payload field a namespace is stored in and filtered on. A namespace
 *   partitions documents inside one collection; `null` or `""`, Koog's default, touches every document.
 */
public class KdrantDocumentStorage<Document>(
    private val client: QdrantClient,
    private val collectionName: String,
    private val embedder: suspend (String) -> List<Float>,
    private val codec: DocumentCodec<Document>,
    private val namespaceKey: String = "namespace",
) : WriteStorage<Document>,
    LookupStorage<Document>,
    SearchStorage<Document, SimilaritySearchRequest>,
    DeletionStorage {

    override suspend fun add(documents: List<Document>, namespace: String?): List<String> {
        if (documents.isEmpty()) return emptyList()
        val ids = documents.map { UUID.randomUUID().toString() }
        store(ids.zip(documents).toMap(), namespace)
        return ids
    }

    override suspend fun update(documents: Map<String, Document>, namespace: String?): List<String> {
        if (documents.isEmpty()) return emptyList()
        store(documents, namespace)
        return documents.keys.toList()
    }

    private suspend fun store(documents: Map<String, Document>, namespace: String?) {
        val vectors = documents.mapValues { (_, document) -> embedder(codec.text(document)) }
        client.upsert(collectionName, wait = true) {
            documents.forEach { (id, document) ->
                point(id) {
                    vector(vectors.getValue(id))
                    payload(payloadOf(document, namespace))
                }
            }
        }
    }

    override suspend fun get(ids: List<String>, namespace: String?): List<Document> {
        if (ids.isEmpty()) return emptyList()
        return client.retrieve(collectionName, ids.map(PointId::uuid), withPayload = WithPayload.All)
            .mapNotNull { record -> record.payload?.let { decode(it) } }
    }

    override suspend fun delete(ids: List<String>, namespace: String?): List<String> {
        if (ids.isEmpty()) return emptyList()
        client.delete(collectionName, ids.map(PointId::uuid), wait = true)
        return ids
    }

    override suspend fun search(
        request: SimilaritySearchRequest,
        namespace: String?,
    ): List<SearchResult<Document>> {
        require(request.filterExpression == null) {
            "kdrant-koog does not translate Koog's filterExpression; filter through the namespace, or " +
                "query Kdrant directly for the full filter DSL"
        }
        val queryVector = embedder(request.queryText)
        val hits = client.search(collectionName) {
            query(queryVector)
            limit = request.limit
            request.offset.takeIf { it > 0 }?.let { offset = it }
            request.minScore?.let { scoreThreshold = it }
            namespace?.takeIf { it.isNotEmpty() }?.let { ns -> filter { must { namespaceKey eq ns } } }
            withPayload = WithPayload.All
        }
        return hits.mapNotNull { hit ->
            val payload = hit.payload ?: return@mapNotNull null
            SearchResult(
                document = decode(payload),
                // Kdrant reports Qdrant's similarity, which is what the collection's distance metric
                // produces. Cosine is the usual choice and the one this reports; a collection created
                // with another metric will have its scores labelled with the wrong name here.
                score = Score(hit.score.toDouble(), ScoreMetric.COSINE_SIMILARITY),
                id = hit.id.asString(),
                metadata = payload,
                namespace = namespace.orEmpty(),
            )
        }
    }

    private fun payloadOf(document: Document, namespace: String?): JsonObject = buildJsonObject {
        codec.payload(document).forEach { (key, value) -> put(key, value) }
        namespace?.takeIf { it.isNotEmpty() }?.let { put(namespaceKey, JsonPrimitive(it)) }
    }

    private fun decode(payload: JsonObject): Document = codec.document(payload)
}

/**
 * How a `Document` becomes a Qdrant point and back.
 *
 * Koog's storage is generic over the document type, and Qdrant stores a vector plus a JSON payload, so
 * something has to say which part of a document is embedded and which part is kept. Use [TextDocuments]
 * when the document is its own text.
 */
public interface DocumentCodec<Document> {

    /** The text [embedder][KdrantDocumentStorage] turns into a vector. */
    public fun text(document: Document): String

    /** The payload stored alongside the vector. */
    public fun payload(document: Document): JsonObject

    /** Rebuild a document from the payload [payload] produced. */
    public fun document(payload: JsonObject): Document
}

/** The codec for documents that are plain text, kept under the `text` payload key. */
public object TextDocuments : DocumentCodec<String> {
    private const val TEXT_KEY = "text"

    override fun text(document: String): String = document

    override fun payload(document: String): JsonObject =
        buildJsonObject { put(TEXT_KEY, JsonPrimitive(document)) }

    override fun document(payload: JsonObject): String =
        (payload[TEXT_KEY] as? JsonPrimitive)?.content.orEmpty()
}

private fun PointId.asString(): String = when (this) {
    is PointId.Num -> value.toString()
    is PointId.Uuid -> value
}
