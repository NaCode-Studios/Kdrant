package dev.kdrant.benchmark

import dev.kdrant.QdrantClient
import dev.kdrant.createCollectionIfNotExists
import dev.kdrant.model.Distance
import dev.kdrant.model.PayloadSchemaType
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
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * What a tenant index is worth, measured rather than described.
 *
 * `2.2.0` made a multi-tenant collection expressible: `isTenant` on a keyword index tells Qdrant to
 * colocate one tenant's points, which is the layout that makes a tenant-filtered search read one
 * tenant's data instead of filtering the whole collection. Nothing here had ever shown that it does
 * anything, and multi-tenancy is the architecture where the difference between the right layout and a
 * nearly right one is a factor rather than a percentage.
 *
 * Two collections hold the same points and the same tenant key. One indexes that key with
 * `isTenant = true`; the other indexes it as an ordinary keyword, which is what a caller who did not
 * know about the flag would have written. The same filtered search runs over both. A third benchmark
 * searches without any filter, so a reader can see what the filter itself costs before the layout does.
 *
 * A small collection is the honest failure case for this: colocation pays when a tenant's points are
 * scattered across many segments, and a collection that fits comfortably is a collection where they are
 * not. If the two rows come out level, that is the number, and it says the layout matters at a size
 * this harness cannot reach rather than that it does not matter.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class MultiTenancyBenchmark {

    private lateinit var client: QdrantClient
    private lateinit var queryVector: List<Float>

    @Setup
    fun setup() {
        val host = System.getenv("QDRANT_HOST") ?: "localhost"
        val port = (System.getenv("QDRANT_PORT") ?: "6333").toInt()
        client = Kdrant(host = host, port = port)
        queryVector = randomVector()
        runBlocking {
            seed(TENANT_INDEXED, tenantOptimized = true)
            seed(PLAIN_INDEXED, tenantOptimized = false)
        }
    }

    private suspend fun seed(collection: String, tenantOptimized: Boolean) {
        client.createCollectionIfNotExists(collection) {
            vector { size = DIM.toLong(); distance = Distance.COSINE }
        }
        // The index goes in before the points, so the layout applies to what is written rather than to
        // what a later optimization pass happens to reach.
        client.createPayloadIndex(collection, TENANT_KEY, wait = true) {
            keyword { isTenant = tenantOptimized }
        }
        var id = 0L
        repeat(TENANTS) { tenant ->
            client.upsert(collection, wait = true) {
                repeat(POINTS_PER_TENANT) {
                    point(++id) {
                        vector(randomVector())
                        payload(TENANT_KEY to "tenant-$tenant")
                    }
                }
            }
        }
    }

    /** One tenant's search over the collection laid out for it. */
    @Benchmark
    fun searchOneTenantWithTenantIndex(): List<ScoredPoint> = runBlocking {
        client.search(TENANT_INDEXED) {
            query(queryVector)
            limit = 10
            filter { must { TENANT_KEY eq "tenant-0" } }
        }
    }

    /** The same search, over a collection whose keyword index does not colocate. */
    @Benchmark
    fun searchOneTenantWithPlainIndex(): List<ScoredPoint> = runBlocking {
        client.search(PLAIN_INDEXED) {
            query(queryVector)
            limit = 10
            filter { must { TENANT_KEY eq "tenant-0" } }
        }
    }

    /** No filter at all, so the two rows above can be read against what a filter costs on its own. */
    @Benchmark
    fun searchUnfiltered(): List<ScoredPoint> = runBlocking {
        client.search(PLAIN_INDEXED) {
            query(queryVector)
            limit = 10
        }
    }

    @TearDown
    fun tearDown() {
        runBlocking {
            runCatching { client.deleteCollection(TENANT_INDEXED) }
            runCatching { client.deleteCollection(PLAIN_INDEXED) }
        }
        client.close()
    }

    private fun randomVector(): List<Float> = List(DIM) { Random.nextFloat() }

    private companion object {
        const val TENANT_INDEXED = "kdrant-bench-tenant-indexed"
        const val PLAIN_INDEXED = "kdrant-bench-tenant-plain"
        const val TENANT_KEY = "tenant"
        const val DIM = 768
        const val TENANTS = 50
        const val POINTS_PER_TENANT = 400
    }
}
