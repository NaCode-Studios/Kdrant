package dev.kdrant.example.rag

import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.createCollectionIfNotExists
import dev.kdrant.dsl.payloadOf
import dev.kdrant.ingest
import dev.kdrant.model.Distance
import dev.kdrant.model.Modifier
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.Tokenizer
import dev.kdrant.model.VectorData
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.rest.Kdrant
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * A minimal, runnable RAG service over Kdrant: the retrieval half of the pipeline.
 *
 * `POST /documents` ingests text; `POST /ask` retrieves the chunks to put in an LLM prompt. It is a
 * demonstration rather than a starter template, so there is nothing to configure: the fastest way to
 * make it useless would be to make it general.
 *
 * It is offline and dependency-free on purpose. Both embedders are toys: a hashed character trigram for
 * the dense vector and a term-frequency map for the sparse one. Swap them for real models and the rest
 * of this file is unchanged, which is the point of it.
 *
 * What it demonstrates, and when each arrived:
 *
 * - `ingest` with a checkpoint on disk, which owns batching, concurrency and resume (2.2.0)
 * - hybrid retrieval fusing a dense and a sparse ranking, which is what a real RAG service does (0.2.0)
 * - a payload index created with the parameters its filters need, without which `matchPhrase` matches
 *   nothing (2.2.0)
 * - a failure path that separates what is worth retrying from what is not (2.2.0)
 */
private const val COLLECTION = "rag-demo"
private const val DENSE = "text"
private const val SPARSE = "bm25"
private const val DIM = 256
private const val SPARSE_TERMS = 4096
private val CHECKPOINT = File(System.getProperty("java.io.tmpdir"), "rag-demo.checkpoint")

/** A toy, deterministic embedder: hashed character trigrams, L2-normalized. Good enough to demo retrieval. */
internal fun embed(text: String): FloatArray {
    val vector = FloatArray(DIM)
    val normalized = text.lowercase()
    if (normalized.length < 3) {
        vector[(normalized.hashCode() % DIM + DIM) % DIM] = 1f
    } else {
        for (i in 0..normalized.length - 3) {
            val bucket = (normalized.substring(i, i + 3).hashCode() % DIM + DIM) % DIM
            vector[bucket] += 1f
        }
    }
    val magnitude = sqrt(vector.fold(0.0) { acc, x -> acc + x * x })
    if (magnitude > 0.0) {
        for (i in vector.indices) vector[i] = (vector[i] / magnitude).toFloat()
    }
    return vector
}

/**
 * The lexical half: term frequencies over hashed words.
 *
 * The collection declares this vector with [Modifier.IDF], so Qdrant applies the inverse document
 * frequency itself from what the collection holds. Sending raw counts and letting the server weight
 * them is the whole reason the modifier exists; computing IDF here would fix it at ingest time and go
 * stale with the next document.
 */
internal fun sparseEmbed(text: String): Pair<List<Int>, List<Float>> {
    val counts = mutableMapOf<Int, Float>()
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.forEach { term ->
        val bucket = (term.hashCode() % SPARSE_TERMS + SPARSE_TERMS) % SPARSE_TERMS
        counts[bucket] = (counts[bucket] ?: 0f) + 1f
    }
    val indices = counts.keys.sorted()
    return indices to indices.map { counts.getValue(it) }
}

@Serializable
internal data class IngestRequest(val documents: List<String>, val lang: String = "en")

@Serializable
internal data class IngestResponse(val ingested: Long, val resumedFrom: Long)

@Serializable
internal data class AskRequest(val question: String, val topK: Int = 3, val lang: String? = null)

@Serializable
internal data class RetrievedChunk(val text: String, val score: Float)

@Serializable
internal data class AskResponse(val question: String, val contexts: List<RetrievedChunk>)

@Serializable
internal data class Failure(val error: String, val retryable: Boolean)

/**
 * The collection a hybrid search needs: one dense vector, one sparse vector with IDF, and the payload
 * indexes the filters below run against.
 *
 * The indexes carry parameters rather than only a type, and one of them decides whether a query works
 * at all: Qdrant matches a phrase only against a text index built with `phraseMatching`, so without it
 * the filter is accepted and matches nothing.
 */
private suspend fun QdrantClient.prepare() {
    createCollectionIfNotExists(COLLECTION) {
        namedVector(DENSE) { size = DIM.toLong(); distance = Distance.COSINE }
        sparseVector(SPARSE) { modifier = Modifier.IDF }
    }
    createPayloadIndex(COLLECTION, "lang", wait = true) { keyword { isTenant = false } }
    createPayloadIndex(COLLECTION, "text", wait = true) {
        text { tokenizer = Tokenizer.WORD; lowercase = true; phraseMatching = true }
    }
}

private fun documentPoint(text: String, lang: String): PointStruct {
    val (indices, values) = sparseEmbed(text)
    return PointStruct(
        id = PointId.uuid(UUID.randomUUID().toString()),
        vector = VectorData.Named(
            mapOf(
                DENSE to VectorData.DenseArray(embed(text)),
                SPARSE to VectorData.Sparse(indices, values),
            ),
        ),
        payload = payloadOf("text" to text, "lang" to lang),
    )
}

fun main() {
    val host = System.getenv("QDRANT_HOST") ?: "localhost"
    val port = (System.getenv("QDRANT_PORT") ?: "6333").toInt()
    val qdrant = Kdrant(host = host, port = port)

    runBlocking { qdrant.prepare() }

    embeddedServer(CIO, port = 8080) {
        install(ContentNegotiation) { json() }
        // Retryable is the distinction a caller acts on: it is the difference between backing off and
        // paging somebody. Catching everything and answering 500 throws that away.
        install(StatusPages) {
            exception<KdrantException> { call, cause ->
                val status = if (cause.retryable) HttpStatusCode.ServiceUnavailable else HttpStatusCode.BadGateway
                call.respond(status, Failure(cause.message ?: "Qdrant refused the request", cause.retryable))
            }
        }
        routing {
            post("/documents") { call.respond(qdrant.ingestDocuments(call.receive())) }
            post("/ask") { call.respond(qdrant.retrieve(call.receive())) }
        }
    }.start(wait = true)
}

/**
 * Ingest through `ingest` rather than `upsert`: it owns the batching, bounds the concurrency and hands
 * back a resume token as the acknowledged prefix grows. The token is written to a file, which is what
 * makes a run killed halfway resumable rather than restartable.
 */
private suspend fun QdrantClient.ingestDocuments(request: IngestRequest): IngestResponse {
    val resumeFrom = readCheckpoint()
    val report = ingest(
        COLLECTION,
        points = request.documents.asFlow().map { documentPoint(it, request.lang) },
        resumeFrom = resumeFrom,
        onCheckpoint = { checkpoint -> CHECKPOINT.writeText(checkpoint.acknowledgedPoints.toString()) },
    )
    val already = resumeFrom?.acknowledgedPoints ?: 0L
    return IngestResponse(
        ingested = report.checkpoint.acknowledgedPoints - already,
        resumedFrom = already,
    )
}

/**
 * Hybrid retrieval: a dense ranking and a sparse one, fused by reciprocal rank. It is what a real RAG
 * service does, because the two fail differently. A dense vector finds a paraphrase and misses a
 * product code; a sparse one finds the code and misses the paraphrase.
 */
private suspend fun QdrantClient.retrieve(request: AskRequest): AskResponse {
    val (indices, values) = sparseEmbed(request.question)
    val hits = search(COLLECTION) {
        prefetch {
            query(embed(request.question).toList())
            using = DENSE
            limit = request.topK * PREFETCH_FACTOR
        }
        prefetch {
            querySparse(indices, values)
            using = SPARSE
            limit = request.topK * PREFETCH_FACTOR
        }
        rrf()
        limit = request.topK
        withPayload = WithPayload.All
        request.lang?.let { lang -> filter { must { "lang" eq lang } } }
    }
    return AskResponse(
        question = request.question,
        contexts = hits.map { hit ->
            RetrievedChunk(
                text = (hit.payload?.get("text") as? JsonPrimitive)?.content ?: "",
                score = hit.score,
            )
        },
    )
}

private fun readCheckpoint(): dev.kdrant.IngestCheckpoint? =
    CHECKPOINT.takeIf { it.exists() }
        ?.readText()
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { dev.kdrant.IngestCheckpoint(acknowledgedPoints = it) }

private const val PREFETCH_FACTOR = 4
