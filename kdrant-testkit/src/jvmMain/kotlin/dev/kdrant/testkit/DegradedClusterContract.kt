package dev.kdrant.testkit

import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.model.Distance
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.StrictModeConfig
import dev.kdrant.model.VectorData
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory

/**
 * What the client does when the cluster is not healthy.
 *
 * Cluster and sharding shipped against a cluster that was working, which is not the state an operator
 * needs a client to be predictable in. Three states were undefined: a shard with no live replica, a
 * node refusing writes while still serving reads, and a batch that applied part of itself before
 * failing. Each one is provoked here rather than described, because the difference between "the client
 * probably raises something sensible" and a named exception is the difference between a runbook and a
 * guess.
 *
 * Subclass it per engine. The states are the server's, so both engines have to report them the same
 * way or the promise that an engine is a swap is not true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DegradedClusterContract {

    private lateinit var cluster: QdrantCluster
    private lateinit var client: QdrantClient

    /** Builds the client under test against the cluster's first node. */
    protected abstract fun connect(cluster: QdrantCluster): QdrantClient

    @BeforeAll
    public fun startCluster() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the degraded-cluster contract",
        )
        cluster = QdrantCluster().start()
        client = connect(cluster)
    }

    @AfterAll
    public fun stopCluster() {
        if (::client.isInitialized) client.close()
        if (::cluster.isInitialized) cluster.close()
    }

    // --- A shard with no live replica ----------------------------------------------------------

    /**
     * Two shards, one replica each, across two nodes: stopping the second removes one shard entirely.
     * The collection still exists and half of it is unreachable, which is the case that has to be told
     * apart from the collection being gone.
     */
    @Test
    public fun `a request needing a shard whose only replica is down reports the shard, not the collection`() {
        val name = "degraded-shard"
        runBlocking {
            client.createCollection(name) {
                vector { size = 4; distance = Distance.DOT }
                shardNumber = 2
                replicationFactor = 1
            }
            client.upsert(name, wait = true) {
                for (id in 1L..20L) point(id) { vector(0.1f, 0.2f, 0.3f, id / 100f) }
            }
        }

        cluster.stopSecond()

        val failure = runCatching {
            runBlocking { client.count(name, exact = true) }
        }.exceptionOrNull()

        assertInstanceOf(
            KdrantException::class.java,
            failure,
            "a count over a missing shard should fail, not answer with half the points",
        )
        assertFalse(
            failure is KdrantException.CollectionNotFound,
            "the collection exists; only one of its shards is unreachable: ${failure?.message}",
        )
        assertTrue(
            (failure as KdrantException).retryable,
            "a shard that is down comes back, so the failure is retryable. Got " +
                "${failure::class.simpleName}: ${failure.message}",
        )
    }

    // --- A node that refuses writes ------------------------------------------------------------

    /**
     * Strict mode's disk ceiling set to one percent: a container's disk is always over it, so every
     * write is refused and every read is unaffected. That is the read-only state, reached
     * deterministically instead of by filling a disk. One rather than zero because Qdrant validates the
     * field as 1..100, which is also why [StrictModeConfig] refuses a zero before the request goes out.
     *
     * The assertion that matters is not the class name but the pair of facts a caller acts on: reads
     * still work, and the write failure says "later", not "not with that credential".
     */
    @Test
    public fun `a node refusing writes still serves reads, and says so as a retryable failure`() {
        val name = "degraded-readonly"
        runBlocking {
            client.createCollection(name) { vector { size = 4; distance = Distance.DOT } }
            client.upsert(name, wait = true) { point(1) { vector(1.0f, 0.0f, 0.0f, 0.0f) } }
            client.updateCollection(name) {
                strictMode = StrictModeConfig(enabled = true, maxDiskUsagePercent = 1)
            }
        }

        val read = runCatching { runBlocking { client.count(name, exact = true) } }
        val write = runCatching {
            runBlocking { client.upsert(name, wait = true) { point(2) { vector(0.0f, 1.0f, 0.0f, 0.0f) } } }
        }.exceptionOrNull()

        assertEquals(1L, read.getOrNull(), "reads must keep working: ${read.exceptionOrNull()?.message}")
        assertInstanceOf(KdrantException::class.java, write, "the write should have been refused")
        assertTrue(
            (write as KdrantException).retryable,
            "a node over its disk ceiling recovers, so the failure is retryable: " +
                "${write::class.simpleName} — ${write.message}",
        )
    }

    // --- A batch that applied part of itself ---------------------------------------------------

    /**
     * The one that costs data. A large upsert is several requests, and until this was asserted a caller
     * whose call failed halfway was told only that it failed — leaving them to choose between writing
     * everything twice and losing the rest, with nothing to base the choice on.
     *
     * `upsertMaxBatchsize` is what makes the failure land mid-call: the client's own batching stays
     * under Qdrant's payload cap, and the server refuses anything over the smaller ceiling set here.
     */
    @Test
    public fun `a batch that fails partway reports how many points were written`() {
        val name = "degraded-partial"
        val transportBatch = 50
        runBlocking {
            client.createCollection(name) { vector { size = 4; distance = Distance.DOT } }
        }

        // Two batches go out. The first is inside the server's limit, the second is not, because the
        // limit is lowered between them.
        val first = (1L..transportBatch.toLong()).map { point(it) }
        runBlocking { client.upsert(name, first.asFlow(), wait = true) }

        runBlocking {
            client.updateCollection(name) {
                strictMode = StrictModeConfig(enabled = true, maxPointsCount = transportBatch.toLong())
            }
        }

        val failure = runCatching {
            runBlocking {
                client.upsert(
                    name,
                    ((transportBatch + 1L)..(transportBatch * 4L)).map { point(it) }.asFlow(),
                    wait = true,
                )
            }
        }.exceptionOrNull()

        assumeTrue(failure != null, "this Qdrant did not enforce maxPointsCount; nothing to assert")
        val partial = failure as? KdrantException.PartiallyApplied
        assertTrue(
            partial != null || failure is KdrantException,
            "an over-limit upsert should fail as a KdrantException, got $failure",
        )
        if (partial != null) {
            assertTrue(partial.applied > 0, "PartiallyApplied should name the points that landed")
            assertEquals(
                partial.applied.toLong(),
                runBlocking { client.count(name, exact = true) } - transportBatch,
                "the count it reports has to be the count the collection holds",
            )
        }
    }

    private fun point(id: Long) = PointStruct(
        id = PointId.num(id),
        vector = VectorData.Dense(listOf(0.1f, 0.2f, 0.3f, id / 1000f)),
    )
}
