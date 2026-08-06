package dev.kdrant.transport.grpc

import dev.kdrant.KdrantConfig
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
import dev.kdrant.model.LocalShardInfo
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation
import dev.kdrant.model.Record
import dev.kdrant.model.RemoteShardInfo
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPair
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.ShardKey
import dev.kdrant.model.ShardTransferInfo
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SnapshotPriority
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import grpc.health.v1.HealthCheck
import grpc.health.v1.HealthGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.kotlin.AbstractCoroutineStub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import qdrant.Collections
import qdrant.CollectionsGrpcKt
import qdrant.Points
import qdrant.PointsGrpcKt
import qdrant.SnapshotsGrpcKt
import qdrant.SnapshotsService
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * The gRPC engine: [QdrantTransport] over Qdrant's `Collections`, `Points`, `Snapshots` and `Health`
 * services.
 *
 * **The seam is wider than the protocol.** `QdrantTransport` was shaped by Qdrant's REST API, and
 * fourteen of its operations have no gRPC equivalent: telemetry, Prometheus metrics, the two issues
 * calls, snapshot recovery, the snapshot and storage-snapshot transfers, and the six shard-scope
 * snapshot operations.
 * They are not silently degraded here. Each throws an [UnsupportedOperationException] naming the
 * operation and pointing at the REST engine, because a snapshot download that quietly returns nothing
 * is a backup that quietly does not exist. A `KdrantException` would have been the wrong type: nothing
 * failed on the wire, and no retry or fallback will make the call work.
 *
 * **Health probes** map to the standard gRPC health checking service rather than to `/healthz`,
 * `/readyz` and `/livez`, which have no gRPC counterpart. Qdrant serves one health status, so the three
 * probes answer from it: a serving node is healthy, ready and alive, and a node that answers anything
 * else — or does not answer — is none of the three. That is the same false-on-failure contract the REST
 * probes have.
 *
 * **Retries** mirror the REST engine's, because [KdrantConfig.maxRetries] is a client setting and an
 * engine that ignored it would be a behaviour difference the caller did not ask for.
 */
public class GrpcQdrantTransport internal constructor(
    private val config: KdrantConfig,
    private val channel: ManagedChannel,
    private val upsertBatchSize: Int,
) : QdrantTransport {

    private val points = PointsGrpcKt.PointsCoroutineStub(channel)
    private val collections = CollectionsGrpcKt.CollectionsCoroutineStub(channel)
    private val snapshots = SnapshotsGrpcKt.SnapshotsCoroutineStub(channel)
    private val health = HealthGrpcKt.HealthCoroutineStub(channel)

    init {
        require(upsertBatchSize > 0) { "upsertBatchSize must be positive, was $upsertBatchSize" }
    }

    // --- Collections -------------------------------------------------------------------------

    override suspend fun createCollection(name: String, request: CreateCollectionRequest) {
        call(name) { collections.deadlined().create(CollectionMapping.createCollection(name, request)) }
    }

    override suspend fun updateCollection(name: String, request: UpdateCollectionRequest) {
        call(name) { collections.deadlined().update(CollectionMapping.updateCollection(name, request)) }
    }

    override suspend fun deleteCollection(name: String) {
        call(name) {
            collections.deadlined().delete(
                Collections.DeleteCollection.newBuilder().setCollectionName(name).build(),
            )
        }
    }

    override suspend fun collectionExists(name: String): Boolean = call(name) {
        collections.deadlined()
            .collectionExists(Collections.CollectionExistsRequest.newBuilder().setCollectionName(name).build())
            .result
            .exists
    }

    override suspend fun getCollection(name: String): CollectionInfo = call(name) {
        CollectionMapping.collectionInfo(
            collections.deadlined()
                .get(Collections.GetCollectionInfoRequest.newBuilder().setCollectionName(name).build())
                .result,
        )
    }

    override suspend fun listCollections(): List<CollectionDescription> = call(null) {
        collections.deadlined()
            .list(Collections.ListCollectionsRequest.getDefaultInstance())
            .collectionsList
            .map { CollectionDescription(it.name) }
    }

    // --- Points ------------------------------------------------------------------------------

    override suspend fun upsert(name: String, points: List<PointStruct>, wait: Boolean) {
        points.chunked(upsertBatchSize).forEach { batch -> upsertBatch(name, batch, wait) }
    }

    override suspend fun upsert(name: String, points: Flow<PointStruct>, wait: Boolean) {
        val batch = ArrayList<PointStruct>(upsertBatchSize)
        points.collect { point ->
            batch += point
            if (batch.size == upsertBatchSize) {
                upsertBatch(name, batch.toList(), wait)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) upsertBatch(name, batch, wait)
    }

    private suspend fun upsertBatch(name: String, batch: List<PointStruct>, wait: Boolean) {
        call(name) {
            points.deadlined().upsert(
                Points.UpsertPoints.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .addAllPoints(batch.map(PointMapping::pointToProto))
                    .build(),
            )
        }
    }

    override suspend fun query(name: String, request: SearchRequest): List<ScoredPoint> = call(name) {
        points.deadlined()
            .query(QueryMapping.queryPoints(name, request))
            .resultList
            .map(PointMapping::scoredPointToModel)
    }

    override suspend fun queryBatch(name: String, requests: List<SearchRequest>): List<List<ScoredPoint>> =
        call(name) {
            points.deadlined()
                .queryBatch(
                    Points.QueryBatchPoints.newBuilder()
                        .setCollectionName(name)
                        .addAllQueryPoints(requests.map { QueryMapping.queryPoints(name, it) })
                        .build(),
                )
                .resultList
                .map { batch -> batch.resultList.map(PointMapping::scoredPointToModel) }
        }

    override suspend fun queryGroups(name: String, request: SearchGroupsRequest): List<PointGroup> = call(name) {
        points.deadlined()
            .queryGroups(QueryMapping.queryGroups(name, request))
            .result
            .groupsList
            .map { group ->
                PointGroup(
                    id = PointMapping.groupId(group.id),
                    hits = group.hitsList.map(PointMapping::scoredPointToModel),
                    lookup = if (group.hasLookup()) PointMapping.recordToModel(group.lookup) else null,
                )
            }
    }

    override suspend fun scroll(name: String, request: ScrollRequest): ScrollPage = call(name) {
        val response = points.deadlined().scroll(RequestMapping.scrollPoints(name, request))
        ScrollPage(
            points = response.resultList.map(PointMapping::recordToModel),
            nextPageOffset = if (response.hasNextPageOffset()) {
                PointMapping.idToModel(response.nextPageOffset)
            } else {
                null
            },
        )
    }

    override suspend fun count(name: String, filter: Filter?, exact: Boolean): Long = call(name) {
        points.deadlined()
            .count(
                Points.CountPoints.newBuilder().apply {
                    collectionName = name
                    this.exact = exact
                    filter?.let { this.filter = FilterMapping.toProto(it) }
                }.build(),
            )
            .result
            .count
    }

    override suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload?,
        withVector: Boolean?,
    ): List<Record> = call(name) {
        points.deadlined()
            .get(
                Points.GetPoints.newBuilder().apply {
                    collectionName = name
                    addAllIds(ids.map(PointMapping::idToProto))
                    RequestMapping.withPayload(withPayload)?.let { setWithPayload(it) }
                    RequestMapping.withVectors(withVector)?.let { setWithVectors(it) }
                }.build(),
            )
            .resultList
            .map(PointMapping::recordToModel)
    }

    override suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean) {
        call(name) {
            points.deadlined().delete(
                Points.DeletePoints.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .setPoints(RequestMapping.pointsSelector(selector))
                    .build(),
            )
        }
    }

    // --- Payload and vectors -----------------------------------------------------------------

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        schema: PayloadSchemaType,
        wait: Boolean,
    ) {
        call(name) {
            points.deadlined().createFieldIndex(
                Points.CreateFieldIndexCollection.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .setFieldName(field)
                    .setFieldType(RequestMapping.fieldType(schema))
                    .build(),
            )
        }
    }

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        params: PayloadIndexParams,
        wait: Boolean,
    ) {
        call(name) {
            points.deadlined().createFieldIndex(
                Points.CreateFieldIndexCollection.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .setFieldName(field)
                    .setFieldType(RequestMapping.fieldType(params))
                    .setFieldIndexParams(RequestMapping.indexParams(params))
                    .build(),
            )
        }
    }

    override suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean) {
        call(name) {
            points.deadlined().deleteFieldIndex(
                Points.DeleteFieldIndexCollection.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .setFieldName(field)
                    .build(),
            )
        }
    }

    override suspend fun setPayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        key: String?,
        wait: Boolean,
    ) {
        call(name) {
            points.deadlined().setPayload(
                Points.SetPayloadPoints.newBuilder().apply {
                    collectionName = name
                    this.wait = wait
                    putAllPayload(PayloadMapping.toProto(payload))
                    pointsSelector = RequestMapping.pointsSelector(selector)
                    key?.let { this.key = it }
                }.build(),
            )
        }
    }

    override suspend fun overwritePayload(name: String, payload: Payload, selector: DeleteSelector, wait: Boolean) {
        call(name) {
            points.deadlined().overwritePayload(
                Points.SetPayloadPoints.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .putAllPayload(PayloadMapping.toProto(payload))
                    .setPointsSelector(RequestMapping.pointsSelector(selector))
                    .build(),
            )
        }
    }

    override suspend fun deletePayload(name: String, keys: List<String>, selector: DeleteSelector, wait: Boolean) {
        call(name) {
            points.deadlined().deletePayload(
                Points.DeletePayloadPoints.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .addAllKeys(keys)
                    .setPointsSelector(RequestMapping.pointsSelector(selector))
                    .build(),
            )
        }
    }

    override suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean) {
        call(name) {
            points.deadlined().clearPayload(
                Points.ClearPayloadPoints.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .setPoints(RequestMapping.pointsSelector(selector))
                    .build(),
            )
        }
    }

    override suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean) {
        call(name) {
            this.points.deadlined().updateVectors(
                Points.UpdatePointVectors.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .addAllPoints(points.map(RequestMapping::pointVectors))
                    .build(),
            )
        }
    }

    override suspend fun deleteVectors(
        name: String,
        vectors: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ) {
        call(name) {
            points.deadlined().deleteVectors(
                Points.DeletePointVectors.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .setPointsSelector(RequestMapping.pointsSelector(selector))
                    .setVectors(Points.VectorsSelector.newBuilder().addAllNames(vectors))
                    .build(),
            )
        }
    }

    override suspend fun batchUpdate(name: String, operations: List<PointsUpdateOperation>, wait: Boolean) {
        call(name) {
            points.deadlined().updateBatch(
                Points.UpdateBatchPoints.newBuilder()
                    .setCollectionName(name)
                    .setWait(wait)
                    .addAllOperations(operations.map(RequestMapping::updateOperation))
                    .build(),
            )
        }
    }

    // --- Aliases -----------------------------------------------------------------------------

    override suspend fun updateAliases(operations: List<AliasOperation>, timeout: Int?) {
        call(null) {
            collections.deadlined().updateAliases(
                Collections.ChangeAliases.newBuilder().apply {
                    addAllActions(operations.map(CollectionMapping::aliasOperation))
                    timeout?.let { this.timeout = it.toLong() }
                }.build(),
            )
        }
    }

    override suspend fun listAliases(): List<AliasDescription> = call(null) {
        collections.deadlined()
            .listAliases(Collections.ListAliasesRequest.getDefaultInstance())
            .aliasesList
            .map { AliasDescription(aliasName = it.aliasName, collectionName = it.collectionName) }
    }

    override suspend fun listCollectionAliases(name: String): List<AliasDescription> = call(name) {
        collections.deadlined()
            .listCollectionAliases(
                Collections.ListCollectionAliasesRequest.newBuilder().setCollectionName(name).build(),
            )
            .aliasesList
            .map { AliasDescription(aliasName = it.aliasName, collectionName = it.collectionName) }
    }

    // --- Service & health --------------------------------------------------------------------

    override suspend fun healthz(): Boolean = serving()

    override suspend fun readyz(): Boolean = serving()

    override suspend fun livez(): Boolean = serving()

    /**
     * The gRPC health service answers one status for the whole node, so the three REST probes answer
     * from it. Any failure is `false`, never an exception: the REST probes report a non-2xx the same
     * way, and a probe that throws is a probe every caller has to wrap.
     */
    private suspend fun serving(): Boolean =
        try {
            withContext(config.dispatcher) {
                health.deadlined().check(HealthCheck.HealthCheckRequest.getDefaultInstance())
                    .status == HealthCheck.HealthCheckResponse.ServingStatus.SERVING
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }

    override suspend fun telemetry(): JsonObject = restOnly("telemetry")

    override suspend fun metrics(): String = restOnly("metrics")

    override suspend fun listIssues(): JsonElement = restOnly("listIssues")

    override suspend fun clearIssues(): Unit = restOnly("clearIssues")

    // --- Analytics ---------------------------------------------------------------------------

    override suspend fun facet(
        name: String,
        key: String,
        filter: Filter?,
        limit: Int?,
        exact: Boolean,
    ): List<FacetHit> = call(name) {
        points.deadlined()
            .facet(
                Points.FacetCounts.newBuilder().apply {
                    collectionName = name
                    this.key = key
                    this.exact = exact
                    filter?.let { this.filter = FilterMapping.toProto(it) }
                    limit?.let { this.limit = it.toLong() }
                }.build(),
            )
            .hitsList
            .map { FacetHit(value = PointMapping.facetValue(it.value), count = it.count) }
    }

    override suspend fun searchMatrixPairs(name: String, request: SearchMatrixRequest): SearchMatrixPairs =
        call(name) {
            SearchMatrixPairs(
                pairs = points.deadlined()
                    .searchMatrixPairs(QueryMapping.searchMatrix(name, request))
                    .result
                    .pairsList
                    .map {
                        SearchMatrixPair(
                            a = PointMapping.idToModel(it.a),
                            b = PointMapping.idToModel(it.b),
                            score = it.score,
                        )
                    },
            )
        }

    override suspend fun searchMatrixOffsets(name: String, request: SearchMatrixRequest): SearchMatrixOffsets =
        call(name) {
            val result = points.deadlined()
                .searchMatrixOffsets(QueryMapping.searchMatrix(name, request))
                .result
            SearchMatrixOffsets(
                offsetsRow = result.offsetsRowList,
                offsetsCol = result.offsetsColList,
                scores = result.scoresList,
                ids = result.idsList.map(PointMapping::idToModel),
            )
        }

    // --- Cluster & sharding ------------------------------------------------------------------

    override suspend fun collectionClusterInfo(name: String): CollectionClusterInfo = call(name) {
        val response = collections.deadlined().collectionClusterInfo(
            Collections.CollectionClusterInfoRequest.newBuilder().setCollectionName(name).build(),
        )
        CollectionClusterInfo(
            peerId = response.peerId,
            shardCount = response.shardCount.toInt(),
            localShards = response.localShardsList.map {
                LocalShardInfo(
                    shardId = it.shardId,
                    shardKey = if (it.hasShardKey()) RequestMapping.shardKeyToModel(it.shardKey) else null,
                    pointsCount = it.pointsCount,
                    state = CollectionMapping.replicaState(it.state),
                )
            },
            remoteShards = response.remoteShardsList.map {
                RemoteShardInfo(
                    shardId = it.shardId,
                    shardKey = if (it.hasShardKey()) RequestMapping.shardKeyToModel(it.shardKey) else null,
                    peerId = it.peerId,
                    state = CollectionMapping.replicaState(it.state),
                )
            },
            shardTransfers = response.shardTransfersList.map {
                ShardTransferInfo(
                    shardId = it.shardId,
                    toShardId = it.takeIf { transfer -> transfer.hasToShardId() }?.toShardId,
                    from = it.from,
                    to = it.to,
                    sync = it.sync,
                )
            },
        )
    }

    override suspend fun updateCollectionCluster(name: String, operation: ClusterOperation, timeout: Int?) {
        call(name) {
            collections.deadlined()
                .updateCollectionClusterSetup(CollectionMapping.clusterSetup(name, operation, timeout))
        }
    }

    override suspend fun createShardKey(name: String, request: CreateShardKeyRequest, timeout: Int?) {
        call(name) {
            collections.deadlined().createShardKey(
                Collections.CreateShardKeyRequest.newBuilder().apply {
                    collectionName = name
                    this.request = Collections.CreateShardKey.newBuilder().apply {
                        shardKey = RequestMapping.shardKey(request.shardKey)
                        request.shardsNumber?.let { shardsNumber = it }
                        request.replicationFactor?.let { replicationFactor = it }
                        request.placement?.let { addAllPlacement(it) }
                    }.build()
                    timeout?.let { this.timeout = it.toLong() }
                }.build(),
            )
        }
    }

    override suspend fun deleteShardKey(name: String, shardKey: ShardKey, timeout: Int?) {
        call(name) {
            collections.deadlined().deleteShardKey(
                Collections.DeleteShardKeyRequest.newBuilder().apply {
                    collectionName = name
                    request = Collections.DeleteShardKey.newBuilder()
                        .setShardKey(RequestMapping.shardKey(shardKey))
                        .build()
                    timeout?.let { this.timeout = it.toLong() }
                }.build(),
            )
        }
    }

    // --- Snapshots ---------------------------------------------------------------------------

    override suspend fun createSnapshot(name: String, wait: Boolean): SnapshotDescription = call(name) {
        CollectionMapping.snapshotDescription(
            snapshots.deadlined()
                .create(SnapshotsService.CreateSnapshotRequest.newBuilder().setCollectionName(name).build())
                .snapshotDescription,
        )
    }

    override suspend fun listSnapshots(name: String): List<SnapshotDescription> = call(name) {
        snapshots.deadlined()
            .list(SnapshotsService.ListSnapshotsRequest.newBuilder().setCollectionName(name).build())
            .snapshotDescriptionsList
            .map(CollectionMapping::snapshotDescription)
    }

    override suspend fun deleteSnapshot(name: String, snapshotName: String, wait: Boolean) {
        call(name) {
            snapshots.deadlined().delete(
                SnapshotsService.DeleteSnapshotRequest.newBuilder()
                    .setCollectionName(name)
                    .setSnapshotName(snapshotName)
                    .build(),
            )
        }
    }

    override suspend fun createStorageSnapshot(wait: Boolean): SnapshotDescription = call(null) {
        CollectionMapping.snapshotDescription(
            snapshots.deadlined()
                .createFull(SnapshotsService.CreateFullSnapshotRequest.getDefaultInstance())
                .snapshotDescription,
        )
    }

    override suspend fun listStorageSnapshots(): List<SnapshotDescription> = call(null) {
        snapshots.deadlined()
            .listFull(SnapshotsService.ListFullSnapshotsRequest.getDefaultInstance())
            .snapshotDescriptionsList
            .map(CollectionMapping::snapshotDescription)
    }

    override suspend fun deleteStorageSnapshot(snapshotName: String, wait: Boolean) {
        call(null) {
            snapshots.deadlined().deleteFull(
                SnapshotsService.DeleteFullSnapshotRequest.newBuilder().setSnapshotName(snapshotName).build(),
            )
        }
    }

    // --- What gRPC does not carry ------------------------------------------------------------

    override suspend fun recoverSnapshot(
        name: String,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = restOnly("recoverSnapshot")

    override fun downloadSnapshot(name: String, snapshotName: String): Flow<ByteArray> =
        restOnly("downloadSnapshot")

    override suspend fun uploadSnapshot(
        name: String,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = restOnly("uploadSnapshot")

    override suspend fun createShardSnapshot(name: String, shardId: Int, wait: Boolean): SnapshotDescription =
        restOnly("createShardSnapshot")

    override suspend fun listShardSnapshots(name: String, shardId: Int): List<SnapshotDescription> =
        restOnly("listShardSnapshots")

    override suspend fun deleteShardSnapshot(name: String, shardId: Int, snapshotName: String, wait: Boolean): Unit =
        restOnly("deleteShardSnapshot")

    override suspend fun recoverShardSnapshot(
        name: String,
        shardId: Int,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = restOnly("recoverShardSnapshot")

    override fun downloadShardSnapshot(name: String, shardId: Int, snapshotName: String): Flow<ByteArray> =
        restOnly("downloadShardSnapshot")

    override suspend fun uploadShardSnapshot(
        name: String,
        shardId: Int,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ): Unit = restOnly("uploadShardSnapshot")

    override fun downloadStorageSnapshot(snapshotName: String): Flow<ByteArray> =
        restOnly("downloadStorageSnapshot")

    override fun close() {
        channel.shutdown()
        if (!channel.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) channel.shutdownNow()
    }

    // --- Plumbing ----------------------------------------------------------------------------

    private fun restOnly(operation: String): Nothing = throw UnsupportedOperationException(
        "$operation has no gRPC equivalent: Qdrant serves it over HTTP only. Use the REST engine " +
            "(kdrant-transport-rest) for it, or for the whole client if you need it often.",
    )

    /**
     * One attempt's deadline. It is set per call rather than on the channel because
     * [KdrantConfig.requestTimeout] is documented as applying to each attempt, and a channel-level
     * deadline would be shared across the retries of one logical request.
     */
    private fun <S : AbstractCoroutineStub<S>> S.deadlined(): S =
        withDeadlineAfter(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /**
     * Runs one operation on the configured dispatcher, retrying what the REST engine retries and
     * translating what is left. [collection] is the collection the call concerns, so a `NOT_FOUND`
     * can name it; `null` for the cluster-wide calls.
     */
    private suspend fun <T> call(collection: String?, block: suspend () -> T): T =
        withContext(config.dispatcher) {
            GrpcErrors.mapping(collection) { withRetries(block) }
        }

    /**
     * The same policy the REST engine gets from Ktor: retry the transient statuses with exponential
     * backoff and jitter, up to [KdrantConfig.maxRetries], and surface anything else at once.
     */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (!retryable(e, attempt)) throw e
            }
            delay(backoff(attempt))
            attempt++
        }
    }

    /**
     * A cancellation is never retried and never swallowed: it falls through to the `throw` above, which
     * is what keeps structured concurrency working.
     */
    private fun retryable(failure: Exception, attempt: Int): Boolean {
        if (failure is CancellationException || attempt >= config.maxRetries) return false
        val code = when (failure) {
            is StatusRuntimeException -> failure.status.code
            is StatusException -> failure.status.code
            else -> return false
        }
        return code == Status.Code.UNAVAILABLE || code == Status.Code.RESOURCE_EXHAUSTED
    }

    private fun backoff(attempt: Int): Long {
        val base = config.retryBaseDelay.inWholeMilliseconds
        val exponential = base shl min(attempt, MAX_BACKOFF_SHIFT)
        val capped = min(exponential, config.retryMaxDelay.inWholeMilliseconds)
        return capped + Random.nextLong(base)
    }
}

private const val SHUTDOWN_GRACE_SECONDS = 5L

/** Caps the doubling at 2^6, so a long-running retry cannot overflow before retryMaxDelay clamps it. */
private const val MAX_BACKOFF_SHIFT = 6
