package dev.kdrant.benchmark

import dev.kdrant.QdrantClient
import dev.kdrant.createCollectionIfNotExists
import dev.kdrant.model.Distance
import dev.kdrant.model.ScoredPoint
import dev.kdrant.transport.rest.Kdrant
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * End-to-end latency benchmarks for `upsert` and `search` against a real Qdrant.
 *
 * Point it at a running Qdrant (`QDRANT_HOST` / `QDRANT_PORT`, default `localhost:6333`) and run:
 * `./gradlew :benchmarks:jmh`. `SampleTime` mode reports the p50/p90/p99 latency distribution.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class KdrantBenchmark {

    private lateinit var client: QdrantClient
    private lateinit var queryVector: List<Float>

    @Setup
    fun setup() {
        val host = System.getenv("QDRANT_HOST") ?: "localhost"
        val port = (System.getenv("QDRANT_PORT") ?: "6333").toInt()
        client = Kdrant(host = host, port = port)
        queryVector = randomVector()
        runBlocking {
            client.createCollectionIfNotExists(COLLECTION) {
                vector { size = DIM.toLong(); distance = Distance.COSINE }
            }
            client.upsert(COLLECTION, wait = true) {
                repeat(SEED_POINTS) { i -> point(i.toLong() + 1) { vector(randomVector()) } }
            }
        }
    }

    @Benchmark
    fun upsertSinglePoint(): Unit = runBlocking {
        client.upsert(COLLECTION, wait = true) {
            point(UUID.randomUUID().toString()) { vector(randomVector()) }
        }
    }

    @Benchmark
    fun searchTopTen(): List<ScoredPoint> = runBlocking {
        client.search(COLLECTION) {
            query(queryVector)
            limit = 10
        }
    }

    @TearDown
    fun tearDown() {
        client.close()
    }

    private fun randomVector(): List<Float> = List(DIM) { Random.nextFloat() }

    private companion object {
        const val COLLECTION = "kdrant-bench"
        const val DIM = 768
        const val SEED_POINTS = 1000
    }
}
