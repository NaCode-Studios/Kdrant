package dev.kdrant.micrometer

import dev.kdrant.KdrantException
import dev.kdrant.dsl.payloadOf
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.CreateShardKeyRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointId
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.ShardKey
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.transport.QdrantTransport
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.search.MeterNotFoundException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The decorator against a stubbed transport: what is under test is that an operation is timed and
 * tagged by name, and a stub is the only way to assert that without also asserting how some engine
 * spells the same call.
 *
 * The other half of the claim — that REST and gRPC produce the same timer — needs two real engines and
 * a real Qdrant, and is asserted in `MetricsAcrossEnginesIntegrationTest`.
 */
class MeteredQdrantTransportTest {

    private val registry = SimpleMeterRegistry()
    private val delegate = mockk<QdrantTransport>(relaxed = true)

    private fun tagsOf(): Map<String, String> =
        registry.get("kdrant.requests").timer().id.tags.associate { it.key to it.value }

    @Test
    fun `an operation is timed under the name the caller asked for, not a URL`() = runTest {
        coEvery { delegate.query(any(), any()) } returns emptyList()

        delegate.metered(registry, tags = listOf(Tag.of("cluster", "eu-1"))).query("docs", SearchRequest(limit = 1))

        assertEquals(1, registry.get("kdrant.requests").timer().count())
        assertEquals(mapOf("cluster" to "eu-1", "operation" to "query", "outcome" to "SUCCESS"), tagsOf())
    }

    @Test
    fun `the collection name is deliberately not a tag`() = runTest {
        coEvery { delegate.query(any(), any()) } returns emptyList()

        delegate.metered(registry).query("tenant-0001", SearchRequest(limit = 1))

        assertNull(tagsOf()["collection"], "a caller-chosen name would make one time series per tenant")
    }

    @Test
    fun `a failure is tagged with the exception the caller would catch`() {
        coEvery { delegate.query(any(), any()) } throws KdrantException.Timeout("took too long")
        val transport = delegate.metered(registry)

        assertThrows(KdrantException.Timeout::class.java) {
            runBlocking { transport.query("docs", SearchRequest(limit = 1)) }
        }

        assertEquals("Timeout", tagsOf()["outcome"])
    }

    @Test
    fun `a cancelled call is not reported as a failure`() {
        coEvery { delegate.query(any(), any()) } throws CancellationException("the caller stopped waiting")
        val transport = delegate.metered(registry)

        assertThrows(CancellationException::class.java) {
            runBlocking { transport.query("docs", SearchRequest(limit = 1)) }
        }

        assertEquals("CANCELLED", tagsOf()["outcome"])
    }

    @Test
    fun `a snapshot download is timed over the whole stream, not the call that returns it`() = runTest {
        every { delegate.downloadSnapshot(any(), any()) } returns flowOf(byteArrayOf(1), byteArrayOf(2))

        val bytes = delegate.metered(registry).downloadSnapshot("docs", "docs.snapshot")

        assertThrows(MeterNotFoundException::class.java) { registry.get("kdrant.requests").timer() }
        assertEquals(2, bytes.toList().size)
        assertEquals("download_snapshot", tagsOf()["operation"])
    }

    @Test
    fun `every operation the caller makes is timed, so a gap in a dashboard is a gap in the calls`() = runTest {
        val transport = delegate.metered(registry)

        transport.createCollection("docs", CreateCollectionRequest())
        transport.healthz()
        transport.listCollections()
        transport.listAliases()

        assertEquals(
            setOf("create_collection", "healthz", "list_collections", "list_aliases"),
            registry.find("kdrant.requests").timers().map { it.id.getTag("operation") }.toSet(),
        )
    }

    /**
     * The decorator's whole promise is that no operation is missed, and a decorator is exactly the
     * kind of code where one is: fifty-six methods, each three lines, added to over years. So every
     * one of them is called and every one has to have produced a timer.
     *
     * The expected names are written out rather than derived from the calls, because a name is what a
     * dashboard is built on: renaming `query` to `search` would break every panel using it, and this
     * list is what makes that a failing test rather than a support ticket.
     */
    // Long by construction: it calls every method the seam has, which is the assertion.
    @Suppress("LongMethod")
    @Test
    fun `every operation on the seam produces a timer, under a name a dashboard can rely on`() = runTest {
        every { delegate.downloadSnapshot(any(), any()) } returns flowOf(byteArrayOf(1))
        every { delegate.downloadShardSnapshot(any(), any(), any()) } returns flowOf(byteArrayOf(1))
        every { delegate.downloadStorageSnapshot(any()) } returns flowOf(byteArrayOf(1))
        val transport = delegate.metered(registry)
        val ids = listOf(PointId.num(1))
        val selector = DeleteSelector.Ids(ids)
        val payload = payloadOf("a" to 1)
        val empty = emptyFlow<ByteArray>()

        with(transport) {
            createCollection("c", CreateCollectionRequest())
            updateCollection("c", UpdateCollectionRequest())
            deleteCollection("c")
            collectionExists("c")
            getCollection("c")
            listCollections()
            upsert("c", emptyList(), wait = true)
            upsert("c", emptyFlow(), wait = true)
            delete("c", selector, wait = true)
            count("c", null, exact = true)
            retrieve("c", ids, null, null)
            scroll("c", ScrollRequest(limit = 1))
            batchUpdate("c", emptyList(), wait = true)
            query("c", SearchRequest(limit = 1))
            queryBatch("c", emptyList())
            queryGroups("c", SearchGroupsRequest(groupBy = "g"))
            createPayloadIndex("c", "f", PayloadSchemaType.KEYWORD, wait = true)
            createPayloadIndex("c", "f", PayloadIndexParams.Keyword(), wait = true)
            deletePayloadIndex("c", "f", wait = true)
            setPayload("c", payload, selector, null, wait = true)
            overwritePayload("c", payload, selector, wait = true)
            deletePayload("c", listOf("a"), selector, wait = true)
            clearPayload("c", selector, wait = true)
            updateVectors("c", emptyList(), wait = true)
            deleteVectors("c", listOf("v"), selector, wait = true)
            updateAliases(emptyList(), null)
            listAliases()
            listCollectionAliases("c")
            healthz()
            readyz()
            livez()
            telemetry()
            metrics()
            listIssues()
            clearIssues()
            facet("c", "k", null, null, exact = true)
            searchMatrixPairs("c", SearchMatrixRequest())
            searchMatrixOffsets("c", SearchMatrixRequest())
            collectionClusterInfo("c")
            updateCollectionCluster("c", ClusterOperation.MoveShard(0, 1, 2), null)
            createShardKey("c", CreateShardKeyRequest(ShardKey.of("k")), null)
            deleteShardKey("c", ShardKey.of("k"), null)
            createSnapshot("c", wait = true)
            listSnapshots("c")
            deleteSnapshot("c", "s", wait = true)
            recoverSnapshot("c", "file:///s", null, null, wait = true)
            downloadSnapshot("c", "s").toList()
            uploadSnapshot("c", empty, null, null, wait = true)
            createShardSnapshot("c", 0, wait = true)
            listShardSnapshots("c", 0)
            deleteShardSnapshot("c", 0, "s", wait = true)
            recoverShardSnapshot("c", 0, "file:///s", null, null, wait = true)
            downloadShardSnapshot("c", 0, "s").toList()
            uploadShardSnapshot("c", 0, empty, null, null, wait = true)
            createStorageSnapshot(wait = true)
            listStorageSnapshots()
            deleteStorageSnapshot("s", wait = true)
            downloadStorageSnapshot("s").toList()
        }

        val timed = registry.find("kdrant.requests").timers().mapNotNull { it.id.getTag("operation") }.toSet()

        assertEquals(EXPECTED_OPERATIONS, timed.toSortedSet().toSet())
    }

    @Test
    fun `close is not an operation and is not timed`() = runTest {
        delegate.metered(registry).close()

        assertThrows(MeterNotFoundException::class.java) { registry.get("kdrant.requests").timer() }
    }

    private companion object {
        /** Every operation the seam has, by the name a dashboard queries it under. */
        val EXPECTED_OPERATIONS: Set<String> = setOf(
            "batch_update",
            "clear_issues",
            "clear_payload",
            "collection_cluster_info",
            "collection_exists",
            "count",
            "create_collection",
            "create_payload_index",
            "create_shard_key",
            "create_shard_snapshot",
            "create_snapshot",
            "create_storage_snapshot",
            "delete",
            "delete_collection",
            "delete_payload",
            "delete_payload_index",
            "delete_shard_key",
            "delete_shard_snapshot",
            "delete_snapshot",
            "delete_storage_snapshot",
            "delete_vectors",
            "download_shard_snapshot",
            "download_snapshot",
            "download_storage_snapshot",
            "facet",
            "get_collection",
            "healthz",
            "list_aliases",
            "list_collection_aliases",
            "list_collections",
            "list_issues",
            "list_shard_snapshots",
            "list_snapshots",
            "list_storage_snapshots",
            "livez",
            "metrics",
            "overwrite_payload",
            "query",
            "query_batch",
            "query_groups",
            "readyz",
            "recover_shard_snapshot",
            "recover_snapshot",
            "retrieve",
            "scroll",
            "search_matrix_offsets",
            "search_matrix_pairs",
            "set_payload",
            "telemetry",
            "update_aliases",
            "update_collection",
            "update_collection_cluster",
            "update_vectors",
            "upload_shard_snapshot",
            "upload_snapshot",
            "upsert",
        )
    }

    @Test
    fun `the prefix names the meter`() = runTest {
        delegate.metered(registry, prefix = "vectors").healthz()

        assertNotNull(registry.find("vectors.requests").timer())
    }
}
