package dev.kdrant.micrometer

import dev.kdrant.model.AliasDescription
import dev.kdrant.model.AliasOperation
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.CollectionClusterInfo
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.CreateShardKeyRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.FacetHit
import dev.kdrant.model.Filter
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.ShardKey
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SnapshotPriority
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One timer per Qdrant operation, recorded above whichever engine executes it.
 *
 * This is the same seam `kdrant-otel` traces on, and for the same reason: a decorator on
 * [QdrantTransport] sees an operation the caller asked for, whatever protocol carries it. The Ktor
 * plugin this replaces could only see requests a Ktor client made, so a client built with `KdrantGrpc`
 * published no metrics at all and nothing said so.
 *
 * Every method is the same three lines, as in the tracing decorator, and for the same reason: an
 * operation left out would be a gap in a dashboard that reads as Qdrant not being called. Unlike the
 * span, the timer never carries the collection, so the operation name is all that is passed down.
 *
 * See [kdrantMetrics] for the meters and their tags.
 */
internal class MeteredQdrantTransport(
    private val delegate: QdrantTransport,
    private val registry: MeterRegistry,
    private val prefix: String,
    tags: Iterable<Tag>,
) : QdrantTransport {

    private val commonTags: List<Tag> = tags.toList()
    private val timerName: String = "$prefix.requests"

    // --- Collections -------------------------------------------------------------------------

    override suspend fun createCollection(name: String, request: CreateCollectionRequest): Unit =
        meter("create_collection") { delegate.createCollection(name, request) }

    override suspend fun updateCollection(name: String, request: UpdateCollectionRequest): Unit =
        meter("update_collection") { delegate.updateCollection(name, request) }

    override suspend fun deleteCollection(name: String): Unit =
        meter("delete_collection") { delegate.deleteCollection(name) }

    override suspend fun collectionExists(name: String): Boolean =
        meter("collection_exists") { delegate.collectionExists(name) }

    override suspend fun getCollection(name: String): CollectionInfo =
        meter("get_collection") { delegate.getCollection(name) }

    override suspend fun listCollections(): List<CollectionDescription> =
        meter("list_collections") { delegate.listCollections() }

    // --- Points ------------------------------------------------------------------------------

    override suspend fun upsert(name: String, points: List<PointStruct>, wait: Boolean): Unit =
        meter("upsert") { delegate.upsert(name, points, wait) }

    override suspend fun upsert(name: String, points: Flow<PointStruct>, wait: Boolean): Unit =
        meter("upsert") { delegate.upsert(name, points, wait) }

    override suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean): Unit =
        meter("delete") { delegate.delete(name, selector, wait) }

    override suspend fun count(name: String, filter: Filter?, exact: Boolean): Long =
        meter("count") { delegate.count(name, filter, exact) }

    override suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload?,
        withVector: Boolean?,
    ): List<Record> = meter("retrieve") { delegate.retrieve(name, ids, withPayload, withVector) }

    override suspend fun scroll(name: String, request: ScrollRequest): ScrollPage =
        meter("scroll") { delegate.scroll(name, request) }

    override suspend fun batchUpdate(name: String, operations: List<PointsUpdateOperation>, wait: Boolean): Unit =
        meter("batch_update") { delegate.batchUpdate(name, operations, wait) }

    // --- Search ------------------------------------------------------------------------------

    override suspend fun query(name: String, request: SearchRequest): List<ScoredPoint> =
        meter("query") { delegate.query(name, request) }

    override suspend fun queryBatch(name: String, requests: List<SearchRequest>): List<List<ScoredPoint>> =
        meter("query_batch") { delegate.queryBatch(name, requests) }

    override suspend fun queryGroups(name: String, request: SearchGroupsRequest): List<PointGroup> =
        meter("query_groups") { delegate.queryGroups(name, request) }

    // --- Payload and vectors -----------------------------------------------------------------

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        schema: PayloadSchemaType,
        wait: Boolean,
    ): Unit = meter("create_payload_index") { delegate.createPayloadIndex(name, field, schema, wait) }

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        params: PayloadIndexParams,
        wait: Boolean,
    ): Unit = meter("create_payload_index") { delegate.createPayloadIndex(name, field, params, wait) }

    override suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean): Unit =
        meter("delete_payload_index") { delegate.deletePayloadIndex(name, field, wait) }

    override suspend fun setPayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        key: String?,
        wait: Boolean,
    ): Unit = meter("set_payload") { delegate.setPayload(name, payload, selector, key, wait) }

    override suspend fun overwritePayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = meter("overwrite_payload") { delegate.overwritePayload(name, payload, selector, wait) }

    override suspend fun deletePayload(
        name: String,
        keys: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = meter("delete_payload") { delegate.deletePayload(name, keys, selector, wait) }

    override suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean): Unit =
        meter("clear_payload") { delegate.clearPayload(name, selector, wait) }

    override suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean): Unit =
        meter("update_vectors") { delegate.updateVectors(name, points, wait) }

    override suspend fun deleteVectors(
        name: String,
        vectors: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = meter("delete_vectors") { delegate.deleteVectors(name, vectors, selector, wait) }

    // --- Aliases -----------------------------------------------------------------------------

    override suspend fun updateAliases(operations: List<AliasOperation>, timeout: Int?): Unit =
        meter("update_aliases") { delegate.updateAliases(operations, timeout) }

    override suspend fun listAliases(): List<AliasDescription> =
        meter("list_aliases") { delegate.listAliases() }

    override suspend fun listCollectionAliases(name: String): List<AliasDescription> =
        meter("list_collection_aliases") { delegate.listCollectionAliases(name) }

    // --- Service & health --------------------------------------------------------------------

    override suspend fun healthz(): Boolean = meter("healthz") { delegate.healthz() }

    override suspend fun readyz(): Boolean = meter("readyz") { delegate.readyz() }

    override suspend fun livez(): Boolean = meter("livez") { delegate.livez() }

    override suspend fun telemetry(): JsonObject = meter("telemetry") { delegate.telemetry() }

    override suspend fun metrics(): String = meter("metrics") { delegate.metrics() }

    override suspend fun listIssues(): JsonElement = meter("list_issues") { delegate.listIssues() }

    override suspend fun clearIssues(): Unit = meter("clear_issues") { delegate.clearIssues() }

    // --- Analytics ---------------------------------------------------------------------------

    override suspend fun facet(
        name: String,
        key: String,
        filter: Filter?,
        limit: Int?,
        exact: Boolean,
    ): List<FacetHit> = meter("facet") { delegate.facet(name, key, filter, limit, exact) }

    override suspend fun searchMatrixPairs(name: String, request: SearchMatrixRequest): SearchMatrixPairs =
        meter("search_matrix_pairs") { delegate.searchMatrixPairs(name, request) }

    override suspend fun searchMatrixOffsets(name: String, request: SearchMatrixRequest): SearchMatrixOffsets =
        meter("search_matrix_offsets") { delegate.searchMatrixOffsets(name, request) }

    // --- Cluster & sharding ------------------------------------------------------------------

    override suspend fun collectionClusterInfo(name: String): CollectionClusterInfo =
        meter("collection_cluster_info") { delegate.collectionClusterInfo(name) }

    override suspend fun updateCollectionCluster(name: String, operation: ClusterOperation, timeout: Int?): Unit =
        meter("update_collection_cluster") { delegate.updateCollectionCluster(name, operation, timeout) }

    override suspend fun createShardKey(name: String, request: CreateShardKeyRequest, timeout: Int?): Unit =
        meter("create_shard_key") { delegate.createShardKey(name, request, timeout) }

    override suspend fun deleteShardKey(name: String, shardKey: ShardKey, timeout: Int?): Unit =
        meter("delete_shard_key") { delegate.deleteShardKey(name, shardKey, timeout) }

    // --- Snapshots ---------------------------------------------------------------------------

    override suspend fun createSnapshot(name: String, wait: Boolean): SnapshotDescription =
        meter("create_snapshot") { delegate.createSnapshot(name, wait) }

    override suspend fun listSnapshots(name: String): List<SnapshotDescription> =
        meter("list_snapshots") { delegate.listSnapshots(name) }

    override suspend fun deleteSnapshot(name: String, snapshotName: String, wait: Boolean): Unit =
        meter("delete_snapshot") { delegate.deleteSnapshot(name, snapshotName, wait) }

    override suspend fun recoverSnapshot(
        name: String,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = meter("recover_snapshot") { delegate.recoverSnapshot(name, location, priority, checksum, wait) }

    override fun downloadSnapshot(name: String, snapshotName: String): Flow<ByteArray> =
        delegate.downloadSnapshot(name, snapshotName).metering("download_snapshot")

    override suspend fun uploadSnapshot(
        name: String,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = meter("upload_snapshot") { delegate.uploadSnapshot(name, data, priority, checksum, wait) }

    override suspend fun createShardSnapshot(name: String, shardId: Int, wait: Boolean): SnapshotDescription =
        meter("create_shard_snapshot") { delegate.createShardSnapshot(name, shardId, wait) }

    override suspend fun listShardSnapshots(name: String, shardId: Int): List<SnapshotDescription> =
        meter("list_shard_snapshots") { delegate.listShardSnapshots(name, shardId) }

    override suspend fun deleteShardSnapshot(
        name: String,
        shardId: Int,
        snapshotName: String,
        wait: Boolean,
    ): Unit = meter("delete_shard_snapshot") { delegate.deleteShardSnapshot(name, shardId, snapshotName, wait) }

    override suspend fun recoverShardSnapshot(
        name: String,
        shardId: Int,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = meter("recover_shard_snapshot") {
        delegate.recoverShardSnapshot(name, shardId, location, priority, checksum, wait)
    }

    override fun downloadShardSnapshot(name: String, shardId: Int, snapshotName: String): Flow<ByteArray> =
        delegate.downloadShardSnapshot(name, shardId, snapshotName).metering("download_shard_snapshot")

    override suspend fun uploadShardSnapshot(
        name: String,
        shardId: Int,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = meter("upload_shard_snapshot") {
        delegate.uploadShardSnapshot(name, shardId, data, priority, checksum, wait)
    }

    override suspend fun createStorageSnapshot(wait: Boolean): SnapshotDescription =
        meter("create_storage_snapshot") { delegate.createStorageSnapshot(wait) }

    override suspend fun listStorageSnapshots(): List<SnapshotDescription> =
        meter("list_storage_snapshots") { delegate.listStorageSnapshots() }

    override suspend fun deleteStorageSnapshot(snapshotName: String, wait: Boolean): Unit =
        meter("delete_storage_snapshot") { delegate.deleteStorageSnapshot(snapshotName, wait) }

    override fun downloadStorageSnapshot(snapshotName: String): Flow<ByteArray> =
        delegate.downloadStorageSnapshot(snapshotName).metering("download_storage_snapshot")

    /** Closing is bookkeeping rather than an operation, and a timer for it would say nothing. */
    override fun close(): Unit = delegate.close()

    // --- The three lines, once -----------------------------------------------------------------

    /**
     * A cancelled call is recorded as `CANCELLED` rather than as a failure. The caller decided to stop
     * waiting, which is not the same event as Qdrant refusing, and a dashboard that cannot tell them
     * apart reports an incident every time a request times out on purpose.
     */
    private suspend fun <T> meter(operation: String, block: suspend () -> T): T {
        val started = Timer.start(registry)
        try {
            val result = block()
            started.record(operation, OUTCOME_SUCCESS)
            return result
        } catch (e: CancellationException) {
            started.record(operation, OUTCOME_CANCELLED)
            throw e
        } catch (e: Throwable) {
            started.record(operation, outcomeOf(e))
            throw e
        }
    }

    /**
     * The snapshot streams are cold flows rather than suspend calls, so the timer measures how long the
     * stream took to drain. That is the number an operator wants from a multi-gigabyte download, and it
     * is not a number the suspend path could produce: the call that returns the flow returns at once.
     */
    private fun <T> Flow<T>.metering(operation: String): Flow<T> {
        val upstream = this
        return flow {
            val started = Timer.start(registry)
            try {
                upstream.collect { emit(it) }
                started.record(operation, OUTCOME_SUCCESS)
            } catch (e: CancellationException) {
                started.record(operation, OUTCOME_CANCELLED)
                throw e
            } catch (e: Throwable) {
                started.record(operation, outcomeOf(e))
                throw e
            }
        }
    }

    /**
     * The collection is deliberately not a tag, which is why it is not a parameter either.
     *
     * A collection name is caller-chosen and unbounded, and a deployment with a collection per tenant
     * would turn one timer into a time series per tenant. The operation is the dimension worth slicing
     * on. `db.collection.name` is on the span instead, where the cost of a high-cardinality attribute
     * is paid per trace rather than per series forever.
     */
    private fun Timer.Sample.record(operation: String, outcome: String) {
        stop(
            Timer.builder(timerName)
                .tags(commonTags)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry),
        )
    }

    /**
     * The exception's simple name, which for a [dev.kdrant.KdrantException] is the failure mode:
     * `Timeout`, `RateLimited`, `CollectionNotFound`. Bounded, because the hierarchy is sealed.
     */
    private fun outcomeOf(error: Throwable): String = error::class.simpleName ?: OUTCOME_FAILURE

    private companion object {
        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_CANCELLED = "CANCELLED"
        const val OUTCOME_FAILURE = "FAILURE"
    }
}
