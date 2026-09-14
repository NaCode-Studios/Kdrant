package dev.kdrant.example.nativeimage

import dev.kdrant.model.Distance
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.rest.Kdrant
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * The smallest application that proves Kdrant works from a native image, and the thing that produces the
 * cold-start number the README quotes.
 *
 * It runs in two parts, and the split is the point.
 *
 * **The timed part** is a cold process answering one search against a collection that already exists. No
 * JVM, no warmup: the clock starts before anything else and stops when the hits are in hand, so what it
 * covers is process start, client construction, connecting, and one round trip.
 *
 * **The untimed part** is the write path, on its own collection, so the image is still proven to create,
 * upsert and delete rather than only to read. Those calls exercise reflection the search path does not,
 * which is the whole reason this example exists.
 *
 * The two were one block until a CI run measured 290 ms against a usual 30. The timed span had included
 * creating a collection and an `upsert` with `wait = true`, which blocks until the write is durable, so
 * the number moved with the server's disk rather than with the client's startup and the README was
 * quoting it as a startup figure. Measure the claim, not the workflow around it.
 *
 * Run it against a Qdrant named by `QDRANT_HOST` and `QDRANT_PORT`, defaulting to a local one. It exits
 * non-zero on any failure, so the CI job that builds the image also proves the image runs.
 */
public fun main() {
    val started = TimeSource.Monotonic.markNow()
    val host = System.getenv("QDRANT_HOST") ?: "localhost"
    val port = System.getenv("QDRANT_PORT")?.toIntOrNull() ?: 6333

    try {
        runBlocking {
            Kdrant(host = host, port = port).use { qdrant ->
                // Seeded by whoever runs this: the CI job does it with curl, and a developer running it
                // locally gets it from the untimed half below on the second run. Timing a search against
                // a collection this process just created would be timing the creation.
                if (!qdrant.collectionExists(READ_COLLECTION)) {
                    System.err.println(
                        "the collection '$READ_COLLECTION' does not exist, so there is no cold search to " +
                            "time. Seeding it now; run this again for the number.",
                    )
                    seed(qdrant)
                    exitProcess(0)
                }

                val hits = qdrant.search(READ_COLLECTION) {
                    query(0.9f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0f)
                    limit = 2
                    withPayload = WithPayload.All
                }
                val elapsed = started.elapsedNow()

                check(hits.size == 2) { "expected 2 hits, got ${hits.size}" }
                check(hits.first().payload?.get("lang").toString().contains("kotlin")) {
                    "the nearest point should be the one aligned with the query, got ${hits.first().id}"
                }

                // Parsed by the CI job that puts the number in the README. Keep the shape stable.
                println("KDRANT_TIME_TO_FIRST_SEARCH_MS=${elapsed.inWholeMilliseconds}")
                println("kdrant native image smoke: ${hits.size} hits from $host:$port in $elapsed")

                // Past the clock. The write path gets exercised because it reaches reflection the read
                // path does not, and an image that can only read is not proven.
                val writeCollection = "$READ_COLLECTION-write"
                if (qdrant.collectionExists(writeCollection)) qdrant.deleteCollection(writeCollection)
                qdrant.createCollection(writeCollection) { vector { size = 8; distance = Distance.COSINE } }
                qdrant.upsert(writeCollection, wait = true) {
                    point(1) { vector(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "kotlin") }
                }
                check(qdrant.count(writeCollection) == 1L) { "the write smoke did not store its point" }
                qdrant.deleteCollection(writeCollection)
                println("kdrant native image smoke: the write path works too")
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

/** Creates the collection the timed search reads, for a developer running this without the CI job. */
private suspend fun seed(qdrant: dev.kdrant.QdrantClient) {
    qdrant.createCollection(READ_COLLECTION) { vector { size = 8; distance = Distance.COSINE } }
    qdrant.upsert(READ_COLLECTION, wait = true) {
        point(1) { vector(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "kotlin") }
        point(2) { vector(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "rust") }
        point(3) { vector(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f); payload("lang" to "go") }
    }
}

private const val READ_COLLECTION = "native-image-smoke"
