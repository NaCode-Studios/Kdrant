package dev.kdrant.example.nativeimage

import dev.kdrant.model.Distance
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.rest.Kdrant
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * The smallest application that proves Kdrant works from a native image: connect, create, upsert,
 * search, clean up.
 *
 * It is deliberately not a demo. Every call here is one a real consumer makes on its first page load,
 * and the number it prints is the one the README quotes: how long a cold process takes to answer its
 * first search. That is half of why anyone asks about native images in the first place.
 *
 * Run it against a Qdrant named by `QDRANT_HOST` and `QDRANT_PORT`, defaulting to a local one. It exits
 * non-zero on any failure, so the CI job that builds the image also proves the image runs.
 */
public fun main() {
    val started = TimeSource.Monotonic.markNow()
    val host = System.getenv("QDRANT_HOST") ?: "localhost"
    val port = System.getenv("QDRANT_PORT")?.toIntOrNull() ?: 6333
    val collection = "native-image-smoke"

    try {
        runBlocking {
            Kdrant(host = host, port = port).use { qdrant ->
                if (qdrant.collectionExists(collection)) qdrant.deleteCollection(collection)
                qdrant.createCollection(collection) { vector { size = 8; distance = Distance.COSINE } }
                qdrant.upsert(collection, wait = true) {
                    point(1) { vector(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "kotlin") }
                    point(2) { vector(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "rust") }
                    point(3) { vector(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "go") }
                }

                val hits = qdrant.search(collection) {
                    query(0.9f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0f)
                    limit = 2
                    withPayload = WithPayload.All
                }
                val elapsed = started.elapsedNow()

                check(hits.size == 2) { "expected 2 hits, got ${hits.size}" }
                check(hits.first().payload?.get("lang").toString().contains("kotlin")) {
                    "the nearest point should be the one aligned with the query, got ${hits.first().id}"
                }

                qdrant.deleteCollection(collection)

                // Parsed by the CI job that puts the number in the README. Keep the shape stable.
                println("KDRANT_TIME_TO_FIRST_SEARCH_MS=${elapsed.inWholeMilliseconds}")
                println("kdrant native image smoke: ${hits.size} hits from $host:$port in $elapsed")
            }
        }
    } catch (failure: Throwable) {
        // Written out rather than thrown, so the CI log shows the frame that failed instead of a
        // native-image process exiting with a code and nothing to read.
        System.err.println("kdrant native image smoke failed against $host:$port")
        System.err.println(failure.stackTraceToString())
        exitProcess(1)
    }
}
