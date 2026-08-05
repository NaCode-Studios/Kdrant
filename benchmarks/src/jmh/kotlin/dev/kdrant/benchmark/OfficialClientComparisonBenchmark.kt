package dev.kdrant.benchmark

import dev.kdrant.QdrantClient
import dev.kdrant.createCollectionIfNotExists
import dev.kdrant.model.Distance
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.VectorData
import dev.kdrant.transport.rest.Kdrant
import io.qdrant.client.QdrantClient as OfficialClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Points
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Kdrant against `io.qdrant:client`, on the same server, the same data and the same JVM.
 *
 * Every number this repository published before this one compared Kdrant to Kdrant: REST against gRPC,
 * boxing against no boxing, a native image's cold start against the JVM's. Those answer questions a
 * user has *after* choosing Kdrant. The question asked *before* choosing it is whether the ergonomics
 * cost anything against the client a team could otherwise use from Kotlin today, and nothing here
 * answered it.
 *
 * An unmeasured suspicion is stronger than a measured deficit, because the reader gets to pick its
 * size. So this measures the four operations that dominate real traffic and publishes the result
 * whichever way it goes.
 *
 * ### Reading it honestly
 *
 * The two clients are not the same shape and the comparison has to say so rather than hide it. The
 * official client speaks gRPC and is measured over gRPC; Kdrant is measured over REST, which is its
 * default and what most callers will use, and over gRPC where the comparison is like for like. A
 * difference between the REST row and the gRPC row is a protocol difference, not a client one.
 *
 * Both are driven from a blocking benchmark thread: `runBlocking` for Kdrant, `get()` on the official
 * client's futures. That measures the same thing for both — end-to-end latency of one operation — and
 * measures neither client's concurrency, which is a separate benchmark and a separate claim.
 *
 * Run it with `./gradlew :benchmarks:jmh -Pjmh.includes=OfficialClientComparison`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class OfficialClientComparisonBenchmark {

    private lateinit var kdrant: QdrantClient
    private lateinit var official: OfficialClient
    private lateinit var queryVector: List<Float>
    private lateinit var batch: List<PointStruct>
    private lateinit var officialBatch: List<Points.PointStruct>

    @Setup
    fun setup() {
        val host = System.getenv("QDRANT_HOST") ?: "localhost"
        val restPort = (System.getenv("QDRANT_PORT") ?: "6333").toInt()
        val grpcPort = (System.getenv("QDRANT_GRPC_PORT") ?: "6334").toInt()

        kdrant = Kdrant(host = host, port = restPort)
        official = OfficialClient(QdrantGrpcClient.newBuilder(host, grpcPort, false).build())

        queryVector = randomVector()
        batch = (1..BATCH).map { index ->
            PointStruct(PointId.num(SEED_POINTS + index.toLong()), VectorData.Dense(randomVector()))
        }
        officialBatch = batch.map { point ->
            Points.PointStruct.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id((point.id as PointId.Num).value.toLong()))
                .setVectors(
                    io.qdrant.client.VectorsFactory.vectors(
                        (point.vector as VectorData.Dense).values,
                    ),
                )
                .build()
        }

        runBlocking {
            kdrant.createCollectionIfNotExists(COLLECTION) {
                vector { size = DIM.toLong(); distance = Distance.COSINE }
            }
            kdrant.upsert(COLLECTION, wait = true) {
                repeat(SEED_POINTS.toInt()) { index -> point(index.toLong() + 1) { vector(randomVector()) } }
            }
        }
    }

    // --- Single search -------------------------------------------------------------------------

    @Benchmark
    fun kdrantSearch(): List<ScoredPoint> = runBlocking {
        kdrant.search(COLLECTION) { query(queryVector); limit = TOP_K }
    }

    @Benchmark
    fun officialSearch(): List<Points.ScoredPoint> =
        official.queryAsync(
            Points.QueryPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setQuery(io.qdrant.client.QueryFactory.nearest(queryVector))
                .setLimit(TOP_K.toLong())
                .build(),
        ).get()

    // --- Batch search --------------------------------------------------------------------------

    @Benchmark
    fun kdrantSearchBatch(): List<List<ScoredPoint>> = runBlocking {
        kdrant.searchBatch(COLLECTION) {
            repeat(BATCH_QUERIES) { search { query(queryVector); limit = TOP_K } }
        }
    }

    @Benchmark
    fun officialSearchBatch(): List<Points.BatchResult> =
        official.queryBatchAsync(
            COLLECTION,
            (1..BATCH_QUERIES).map {
                Points.QueryPoints.newBuilder()
                    .setCollectionName(COLLECTION)
                    .setQuery(io.qdrant.client.QueryFactory.nearest(queryVector))
                    .setLimit(TOP_K.toLong())
                    .build()
            },
        ).get()

    // --- Upsert of a large batch ---------------------------------------------------------------

    @Benchmark
    fun kdrantUpsertBatch(): Unit = runBlocking {
        kdrant.upsert(COLLECTION, batch.asSequence(), wait = true)
    }

    @Benchmark
    fun officialUpsertBatch(): Points.UpdateResult =
        official.upsertAsync(COLLECTION, officialBatch).get()

    // --- Scroll --------------------------------------------------------------------------------

    @Benchmark
    fun kdrantScroll(): Int = runBlocking {
        kdrant.scroll(COLLECTION, pageSize = SCROLL_PAGE).toList().size
    }

    @Benchmark
    fun officialScroll(): Int {
        var offset: Points.PointId? = null
        var seen = 0
        while (true) {
            val request = Points.ScrollPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setLimit(SCROLL_PAGE)
            offset?.let { request.setOffset(it) }
            val page = official.scrollAsync(request.build()).get()
            seen += page.resultCount
            if (!page.hasNextPageOffset() || page.resultCount == 0) return seen
            offset = page.nextPageOffset
        }
    }

    @TearDown
    fun tearDown() {
        kdrant.close()
        official.close()
    }

    private fun randomVector(): List<Float> = List(DIM) { Random.nextFloat() }

    private companion object {
        const val COLLECTION = "kdrant-vs-official"
        const val DIM = 384
        const val SEED_POINTS = 2_000L
        const val TOP_K = 10
        const val BATCH = 500
        const val BATCH_QUERIES = 10
        const val SCROLL_PAGE = 256
    }
}
