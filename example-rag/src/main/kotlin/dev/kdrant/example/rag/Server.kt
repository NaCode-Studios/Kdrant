package dev.kdrant.example.rag

import dev.kdrant.createCollectionIfNotExists
import dev.kdrant.model.Distance
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.rest.Kdrant
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.math.sqrt

/**
 * A minimal, runnable Retrieval-Augmented-Generation example over Kdrant.
 *
 * `POST /documents` embeds and stores text; `POST /ask` embeds a question and returns the most similar
 * stored chunks (the retrieval half of RAG — feed these into your LLM prompt to generate an answer).
 *
 * To keep the demo dependency-free and offline, it embeds with a tiny deterministic char-trigram hash.
 * Swap [embed] for a real model (OpenAI, or an in-process one via `kdrant-langchain4j`) for real quality.
 */
private const val COLLECTION = "rag-demo"
private const val DIM = 256

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

@Serializable
internal data class IngestRequest(val documents: List<String>)

@Serializable
internal data class IngestResponse(val ingested: Int)

@Serializable
internal data class AskRequest(val question: String, val topK: Int = 3)

@Serializable
internal data class RetrievedChunk(val text: String, val score: Float)

@Serializable
internal data class AskResponse(val question: String, val contexts: List<RetrievedChunk>)

fun main() {
    val host = System.getenv("QDRANT_HOST") ?: "localhost"
    val port = (System.getenv("QDRANT_PORT") ?: "6333").toInt()
    val qdrant = Kdrant(host = host, port = port)

    runBlocking {
        qdrant.createCollectionIfNotExists(COLLECTION) {
            vector { size = DIM.toLong(); distance = Distance.COSINE }
        }
    }

    embeddedServer(CIO, port = 8080) {
        install(ContentNegotiation) { json() }
        routing {
            post("/documents") {
                val request = call.receive<IngestRequest>()
                qdrant.upsert(COLLECTION, wait = true) {
                    request.documents.forEach { text ->
                        point(UUID.randomUUID().toString()) {
                            vector(*embed(text))
                            payload("text" to text)
                        }
                    }
                }
                call.respond(IngestResponse(request.documents.size))
            }
            post("/ask") {
                val request = call.receive<AskRequest>()
                val hits = qdrant.search(COLLECTION) {
                    query(*embed(request.question))
                    limit = request.topK
                    withPayload = WithPayload.All
                }
                val contexts = hits.map { hit ->
                    RetrievedChunk(
                        text = (hit.payload?.get("text") as? JsonPrimitive)?.content ?: "",
                        score = hit.score,
                    )
                }
                call.respond(AskResponse(request.question, contexts))
            }
        }
    }.start(wait = true)
}
