package dev.kdrant.otel

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
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One client span per Qdrant operation, wrapped around whichever engine executes it.
 *
 * Every method here is the same three lines: open a span named after the operation, run the delegate
 * inside it, close it. The repetition is the point — a decorator that covered only the interesting
 * operations would leave the caller guessing which gaps in a trace are Kdrant not looking and which
 * are Kdrant not being called.
 *
 * See [kdrantTracing] for what the spans carry and, more importantly, what they do not.
 */
internal class TracingQdrantTransport(
    private val delegate: QdrantTransport,
    openTelemetry: OpenTelemetry,
    private val serverAddress: String?,
    private val serverPort: Int?,
) : QdrantTransport {

    private val tracer: Tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME)

    // --- Collections -------------------------------------------------------------------------

    override suspend fun createCollection(name: String, request: CreateCollectionRequest): Unit =
        span("create_collection", name) { delegate.createCollection(name, request) }

    override suspend fun updateCollection(name: String, request: UpdateCollectionRequest): Unit =
        span("update_collection", name) { delegate.updateCollection(name, request) }

    override suspend fun deleteCollection(name: String): Unit =
        span("delete_collection", name) { delegate.deleteCollection(name) }

    override suspend fun collectionExists(name: String): Boolean =
        span("collection_exists", name) { delegate.collectionExists(name) }

    override suspend fun getCollection(name: String): CollectionInfo =
        span("get_collection", name) { delegate.getCollection(name) }

    override suspend fun listCollections(): List<CollectionDescription> =
        span("list_collections", null) { delegate.listCollections() }

    // --- Points ------------------------------------------------------------------------------

    override suspend fun upsert(name: String, points: List<PointStruct>, wait: Boolean): Unit =
        span("upsert", name) { delegate.upsert(name, points, wait) }

    override suspend fun upsert(name: String, points: Flow<PointStruct>, wait: Boolean): Unit =
        span("upsert", name) { delegate.upsert(name, points, wait) }

    override suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean): Unit =
        span("delete", name) { delegate.delete(name, selector, wait) }

    override suspend fun count(name: String, filter: Filter?, exact: Boolean): Long =
        span("count", name) { delegate.count(name, filter, exact) }

    override suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload?,
        withVector: Boolean?,
    ): List<Record> = span("retrieve", name) { delegate.retrieve(name, ids, withPayload, withVector) }

    override suspend fun scroll(name: String, request: ScrollRequest): ScrollPage =
        span("scroll", name) { delegate.scroll(name, request) }

    override suspend fun batchUpdate(name: String, operations: List<PointsUpdateOperation>, wait: Boolean): Unit =
        span("batch_update", name) { delegate.batchUpdate(name, operations, wait) }

    // --- Search ------------------------------------------------------------------------------

    override suspend fun query(name: String, request: SearchRequest): List<ScoredPoint> =
        span("query", name) { delegate.query(name, request) }

    override suspend fun queryBatch(name: String, requests: List<SearchRequest>): List<List<ScoredPoint>> =
        span("query_batch", name) { delegate.queryBatch(name, requests) }

    override suspend fun queryGroups(name: String, request: SearchGroupsRequest): List<PointGroup> =
        span("query_groups", name) { delegate.queryGroups(name, request) }

    // --- Payload and vectors -----------------------------------------------------------------

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        schema: PayloadSchemaType,
        wait: Boolean,
    ): Unit = span("create_payload_index", name) { delegate.createPayloadIndex(name, field, schema, wait) }

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        params: PayloadIndexParams,
        wait: Boolean,
    ): Unit = span("create_payload_index", name) { delegate.createPayloadIndex(name, field, params, wait) }

    override suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean): Unit =
        span("delete_payload_index", name) { delegate.deletePayloadIndex(name, field, wait) }

    override suspend fun setPayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        key: String?,
        wait: Boolean,
    ): Unit = span("set_payload", name) { delegate.setPayload(name, payload, selector, key, wait) }

    override suspend fun overwritePayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = span("overwrite_payload", name) { delegate.overwritePayload(name, payload, selector, wait) }

    override suspend fun deletePayload(
        name: String,
        keys: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = span("delete_payload", name) { delegate.deletePayload(name, keys, selector, wait) }

    override suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean): Unit =
        span("clear_payload", name) { delegate.clearPayload(name, selector, wait) }

    override suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean): Unit =
        span("update_vectors", name) { delegate.updateVectors(name, points, wait) }

    override suspend fun deleteVectors(
        name: String,
        vectors: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = span("delete_vectors", name) { delegate.deleteVectors(name, vectors, selector, wait) }

    // --- Aliases -----------------------------------------------------------------------------

    override suspend fun updateAliases(operations: List<AliasOperation>, timeout: Int?): Unit =
        span("update_aliases", null) { delegate.updateAliases(operations, timeout) }

    override suspend fun listAliases(): List<AliasDescription> =
        span("list_aliases", null) { delegate.listAliases() }

    override suspend fun listCollectionAliases(name: String): List<AliasDescription> =
        span("list_collection_aliases", name) { delegate.listCollectionAliases(name) }

    // --- Service & health --------------------------------------------------------------------

    override suspend fun healthz(): Boolean = span("healthz", null) { delegate.healthz() }

    override suspend fun readyz(): Boolean = span("readyz", null) { delegate.readyz() }

    override suspend fun livez(): Boolean = span("livez", null) { delegate.livez() }

    override suspend fun telemetry(): JsonObject = span("telemetry", null) { delegate.telemetry() }

    override suspend fun metrics(): String = span("metrics", null) { delegate.metrics() }

    override suspend fun listIssues(): JsonElement = span("list_issues", null) { delegate.listIssues() }

    override suspend fun clearIssues(): Unit = span("clear_issues", null) { delegate.clearIssues() }

    // --- Analytics ---------------------------------------------------------------------------

    override suspend fun facet(
        name: String,
        key: String,
        filter: Filter?,
        limit: Int?,
        exact: Boolean,
    ): List<FacetHit> = span("facet", name) { delegate.facet(name, key, filter, limit, exact) }

    override suspend fun searchMatrixPairs(name: String, request: SearchMatrixRequest): SearchMatrixPairs =
        span("search_matrix_pairs", name) { delegate.searchMatrixPairs(name, request) }

    override suspend fun searchMatrixOffsets(name: String, request: SearchMatrixRequest): SearchMatrixOffsets =
        span("search_matrix_offsets", name) { delegate.searchMatrixOffsets(name, request) }

    // --- Cluster & sharding ------------------------------------------------------------------

    override suspend fun collectionClusterInfo(name: String): CollectionClusterInfo =
        span("collection_cluster_info", name) { delegate.collectionClusterInfo(name) }

    override suspend fun updateCollectionCluster(name: String, operation: ClusterOperation, timeout: Int?): Unit =
        span("update_collection_cluster", name) { delegate.updateCollectionCluster(name, operation, timeout) }

    override suspend fun createShardKey(name: String, request: CreateShardKeyRequest, timeout: Int?): Unit =
        span("create_shard_key", name) { delegate.createShardKey(name, request, timeout) }

    override suspend fun deleteShardKey(name: String, shardKey: ShardKey, timeout: Int?): Unit =
        span("delete_shard_key", name) { delegate.deleteShardKey(name, shardKey, timeout) }

    // --- Snapshots ---------------------------------------------------------------------------

    override suspend fun createSnapshot(name: String, wait: Boolean): SnapshotDescription =
        span("create_snapshot", name) { delegate.createSnapshot(name, wait) }

    override suspend fun listSnapshots(name: String): List<SnapshotDescription> =
        span("list_snapshots", name) { delegate.listSnapshots(name) }

    override suspend fun deleteSnapshot(name: String, snapshotName: String, wait: Boolean): Unit =
        span("delete_snapshot", name) { delegate.deleteSnapshot(name, snapshotName, wait) }

    override suspend fun recoverSnapshot(
        name: String,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = span("recover_snapshot", name) { delegate.recoverSnapshot(name, location, priority, checksum, wait) }

    override fun downloadSnapshot(name: String, snapshotName: String): Flow<ByteArray> =
        delegate.downloadSnapshot(name, snapshotName).spanning("download_snapshot", name)

    override suspend fun uploadSnapshot(
        name: String,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = span("upload_snapshot", name) { delegate.uploadSnapshot(name, data, priority, checksum, wait) }

    override suspend fun createShardSnapshot(name: String, shardId: Int, wait: Boolean): SnapshotDescription =
        span("create_shard_snapshot", name) { delegate.createShardSnapshot(name, shardId, wait) }

    override suspend fun listShardSnapshots(name: String, shardId: Int): List<SnapshotDescription> =
        span("list_shard_snapshots", name) { delegate.listShardSnapshots(name, shardId) }

    override suspend fun deleteShardSnapshot(
        name: String,
        shardId: Int,
        snapshotName: String,
        wait: Boolean,
    ): Unit = span("delete_shard_snapshot", name) { delegate.deleteShardSnapshot(name, shardId, snapshotName, wait) }

    override suspend fun recoverShardSnapshot(
        name: String,
        shardId: Int,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = span("recover_shard_snapshot", name) {
        delegate.recoverShardSnapshot(name, shardId, location, priority, checksum, wait)
    }

    override fun downloadShardSnapshot(name: String, shardId: Int, snapshotName: String): Flow<ByteArray> =
        delegate.downloadShardSnapshot(name, shardId, snapshotName).spanning("download_shard_snapshot", name)

    override suspend fun uploadShardSnapshot(
        name: String,
        shardId: Int,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = span("upload_shard_snapshot", name) {
        delegate.uploadShardSnapshot(name, shardId, data, priority, checksum, wait)
    }

    override suspend fun createStorageSnapshot(wait: Boolean): SnapshotDescription =
        span("create_storage_snapshot", null) { delegate.createStorageSnapshot(wait) }

    override suspend fun listStorageSnapshots(): List<SnapshotDescription> =
        span("list_storage_snapshots", null) { delegate.listStorageSnapshots() }

    override suspend fun deleteStorageSnapshot(snapshotName: String, wait: Boolean): Unit =
        span("delete_storage_snapshot", null) { delegate.deleteStorageSnapshot(snapshotName, wait) }

    override fun downloadStorageSnapshot(snapshotName: String): Flow<ByteArray> =
        delegate.downloadStorageSnapshot(snapshotName).spanning("download_storage_snapshot", null)

    /** Closing is bookkeeping rather than an operation, and a span for it would say nothing. */
    override fun close(): Unit = delegate.close()

    // --- The three lines, once -----------------------------------------------------------------

    private fun start(operation: String, collection: String?): Span =
        tracer.spanBuilder(if (collection == null) operation else "$operation $collection")
            .setSpanKind(SpanKind.CLIENT)
            .setAllAttributes(attributes(operation, collection))
            .startSpan()

    private fun attributes(operation: String, collection: String?): Attributes {
        val builder: AttributesBuilder = Attributes.builder()
            .put(KdrantAttributes.DB_SYSTEM_NAME, KdrantAttributes.QDRANT)
            .put(KdrantAttributes.DB_OPERATION_NAME, operation)
        collection?.let { builder.put(KdrantAttributes.DB_COLLECTION_NAME, it) }
        serverAddress?.let { builder.put(KdrantAttributes.SERVER_ADDRESS, it) }
        serverPort?.let { builder.put(KdrantAttributes.SERVER_PORT, it.toLong()) }
        return builder.build()
    }

    /**
     * The span is made current for the duration of the call, so anything the delegate traces — an
     * engine's own HTTP instrumentation, for one — nests under it rather than beside it.
     *
     * A cancelled call ends its span without marking it an error: a cancellation is the caller's
     * decision, and a trace full of red spans for a timed-out request the caller abandoned on purpose
     * is a trace nobody reads twice.
     */
    private suspend fun <T> span(operation: String, collection: String?, block: suspend () -> T): T {
        val span = start(operation, collection)
        try {
            return withContext(span.asContextElement()) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            span.fail(e)
            throw e
        } finally {
            span.end()
        }
    }

    /**
     * The snapshot streams are cold flows rather than suspend calls, so the span covers the collection
     * of the stream. It is deliberately not made current: a byte stream has no child calls to parent,
     * and holding a context across an arbitrary collector's dispatcher would be a way to leak one.
     */
    private fun <T> Flow<T>.spanning(operation: String, collection: String?): Flow<T> {
        val upstream = this
        return flow {
            val span = start(operation, collection)
            try {
                upstream.collect { emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                span.fail(e)
                throw e
            } finally {
                span.end()
            }
        }
    }

    /**
     * Records the failure as its type and nothing else. Qdrant's error messages quote the request
     * back, and a request can be a filter naming a tenant; the type is what a dashboard groups on
     * anyway.
     */
    private fun Span.fail(error: Throwable) {
        val type = error::class.qualifiedName ?: error::class.java.name
        setAttribute(KdrantAttributes.ERROR_TYPE, type)
        setStatus(StatusCode.ERROR, type)
    }
}
