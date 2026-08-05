package dev.kdrant.transport.grpc

import dev.kdrant.model.AliasOperation
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.CollectionConfig
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CollectionParams
import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.Distance
import dev.kdrant.model.HnswConfig
import dev.kdrant.model.Modifier
import dev.kdrant.model.MultiVectorComparator
import dev.kdrant.model.MultiVectorConfig
import dev.kdrant.model.OptimizersConfig
import dev.kdrant.model.PayloadIndexInfo
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.QuantizationConfig
import dev.kdrant.model.ReplicaState
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SparseVectorParams
import dev.kdrant.model.StrictModeConfig
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.model.VectorDatatype
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import qdrant.Collections
import qdrant.SnapshotsService
import java.time.Instant

/**
 * Collection configuration and the read-back of it.
 *
 * The asymmetry worth knowing about: `CreateCollection` takes whole config messages, while
 * `UpdateCollection` takes `*Diff` messages of the same shape. Kdrant's models are already diffs —
 * every field is nullable and an unset field means "leave it alone" — so the two directions differ
 * only in which protobuf type they build, not in what they read.
 *
 * Product and Turbo quantization exist on the wire and not in the model, so a collection created
 * elsewhere with either reads back as no quantization rather than as something it is not. The REST
 * engine drops them the same way, because the model is the same.
 */
internal object CollectionMapping {

    fun createCollection(name: String, request: CreateCollectionRequest): Collections.CreateCollection =
        Collections.CreateCollection.newBuilder().apply {
            collectionName = name
            request.vectors?.let { vectorsConfig = vectorsConfig(it) }
            request.sparseVectors?.let { sparseVectorsConfig = sparseVectorConfig(it) }
            request.hnswConfig?.let { hnswConfig = hnswConfig(it) }
            request.onDiskPayload?.let { onDiskPayload = it }
            request.shardNumber?.let { shardNumber = it }
            request.replicationFactor?.let { replicationFactor = it }
            request.optimizersConfig?.let { optimizersConfig = optimizersConfig(it) }
            request.quantizationConfig?.let { quantizationConfig = quantizationConfig(it) }
            request.strictModeConfig?.let { strictModeConfig = strictModeConfig(it) }
        }.build()

    fun updateCollection(name: String, request: UpdateCollectionRequest): Collections.UpdateCollection =
        Collections.UpdateCollection.newBuilder().apply {
            collectionName = name
            request.optimizersConfig?.let { optimizersConfig = optimizersConfig(it) }
            request.hnswConfig?.let { hnswConfig = hnswConfig(it) }
            request.quantizationConfig?.let { quantizationConfig = quantizationConfigDiff(it) }
            request.strictModeConfig?.let { strictModeConfig = strictModeConfig(it) }
        }.build()

    fun collectionInfo(info: Collections.CollectionInfo): CollectionInfo = CollectionInfo(
        status = status(info.status),
        pointsCount = info.takeIf { it.hasPointsCount() }?.pointsCount,
        indexedVectorsCount = info.takeIf { it.hasIndexedVectorsCount() }?.indexedVectorsCount,
        segmentsCount = info.segmentsCount.toInt(),
        config = if (info.hasConfig()) collectionConfig(info.config) else null,
        payloadSchema = info.payloadSchemaMap.mapValues { (_, schema) -> payloadIndexInfo(schema) },
    )

    fun snapshotDescription(description: SnapshotsService.SnapshotDescription): SnapshotDescription =
        SnapshotDescription(
            name = description.name,
            // The wire carries an instant; the model carries the RFC 3339 text REST returns.
            creationTime = description.takeIf { it.hasCreationTime() }
                ?.creationTime
                ?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()).toString() },
            size = description.size,
            checksum = description.takeIf { it.hasChecksum() }?.checksum,
        )

    fun payloadSchemaType(type: PayloadSchemaType): Collections.PayloadSchemaType = when (type) {
        PayloadSchemaType.KEYWORD -> Collections.PayloadSchemaType.Keyword
        PayloadSchemaType.INTEGER -> Collections.PayloadSchemaType.Integer
        PayloadSchemaType.FLOAT -> Collections.PayloadSchemaType.Float
        PayloadSchemaType.GEO -> Collections.PayloadSchemaType.Geo
        PayloadSchemaType.TEXT -> Collections.PayloadSchemaType.Text
        PayloadSchemaType.BOOL -> Collections.PayloadSchemaType.Bool
        PayloadSchemaType.DATETIME -> Collections.PayloadSchemaType.Datetime
        PayloadSchemaType.UUID -> Collections.PayloadSchemaType.Uuid
    }

    fun aliasOperation(operation: AliasOperation): Collections.AliasOperations =
        Collections.AliasOperations.newBuilder().apply {
            when (operation) {
                is AliasOperation.Create -> createAlias = Collections.CreateAlias.newBuilder()
                    .setCollectionName(operation.collectionName)
                    .setAliasName(operation.aliasName)
                    .build()
                is AliasOperation.Delete -> deleteAlias = Collections.DeleteAlias.newBuilder()
                    .setAliasName(operation.aliasName)
                    .build()
                is AliasOperation.Rename -> renameAlias = Collections.RenameAlias.newBuilder()
                    .setOldAliasName(operation.oldAliasName)
                    .setNewAliasName(operation.newAliasName)
                    .build()
            }
        }.build()

    /** [timeout] is in seconds, as everywhere else on the seam. */
    fun clusterSetup(
        name: String,
        operation: ClusterOperation,
        timeout: Int?,
    ): Collections.UpdateCollectionClusterSetupRequest =
        Collections.UpdateCollectionClusterSetupRequest.newBuilder().apply {
            collectionName = name
            timeout?.let { this.timeout = it.toLong() }
            when (operation) {
                is ClusterOperation.MoveShard -> moveShard = Collections.MoveShard.newBuilder()
                    .setShardId(operation.shardId)
                    .setFromPeerId(operation.fromPeerId)
                    .setToPeerId(operation.toPeerId)
                    .build()
                is ClusterOperation.ReplicateShard -> replicateShard = Collections.ReplicateShard.newBuilder()
                    .setShardId(operation.shardId)
                    .setFromPeerId(operation.fromPeerId)
                    .setToPeerId(operation.toPeerId)
                    .build()
                is ClusterOperation.AbortTransfer -> abortTransfer = Collections.AbortShardTransfer.newBuilder()
                    .setShardId(operation.shardId)
                    .setFromPeerId(operation.fromPeerId)
                    .setToPeerId(operation.toPeerId)
                    .build()
                is ClusterOperation.DropReplica -> dropReplica = Collections.Replica.newBuilder()
                    .setShardId(operation.shardId)
                    .setPeerId(operation.peerId)
                    .build()
            }
        }.build()

    fun replicaState(state: Collections.ReplicaState): ReplicaState = when (state) {
        Collections.ReplicaState.Active -> ReplicaState.ACTIVE
        Collections.ReplicaState.Dead -> ReplicaState.DEAD
        Collections.ReplicaState.Partial -> ReplicaState.PARTIAL
        Collections.ReplicaState.Initializing -> ReplicaState.INITIALIZING
        Collections.ReplicaState.Listener -> ReplicaState.LISTENER
        Collections.ReplicaState.PartialSnapshot -> ReplicaState.PARTIAL_SNAPSHOT
        Collections.ReplicaState.Recovery -> ReplicaState.RECOVERY
        Collections.ReplicaState.Resharding -> ReplicaState.RESHARDING
        Collections.ReplicaState.ReshardingScaleDown -> ReplicaState.RESHARDING_SCALE_DOWN
        Collections.ReplicaState.ActiveRead -> ReplicaState.ACTIVE_READ
        // A state a newer Qdrant added. The model already decodes an unrecognized state to UNKNOWN
        // rather than failing the whole cluster-info response; the same tolerance applies here.
        else -> ReplicaState.UNKNOWN
    }

    private fun collectionConfig(config: Collections.CollectionConfig): CollectionConfig = CollectionConfig(
        params = if (config.hasParams()) collectionParams(config.params) else null,
    )

    private fun collectionParams(params: Collections.CollectionParams): CollectionParams = CollectionParams(
        vectors = if (params.hasVectorsConfig()) vectorsConfigToModel(params.vectorsConfig) else null,
        sparseVectors = params.takeIf { it.hasSparseVectorsConfig() }
            ?.sparseVectorsConfig
            ?.mapMap
            ?.mapValues { (_, value) -> sparseVectorParamsToModel(value) },
        shardNumber = params.shardNumber,
        replicationFactor = params.takeIf { it.hasReplicationFactor() }?.replicationFactor,
        writeConsistencyFactor = params.takeIf { it.hasWriteConsistencyFactor() }?.writeConsistencyFactor,
        onDiskPayload = params.onDiskPayload,
    )

    /**
     * The index type is kept as its wire string rather than as the enum, matching the REST engine: an
     * index type a newer Qdrant adds decodes to a name this client does not know instead of failing
     * the whole `getCollection` response.
     */
    private fun payloadIndexInfo(schema: Collections.PayloadSchemaInfo): PayloadIndexInfo = PayloadIndexInfo(
        dataType = schema.dataType.name.lowercase().takeUnless { it == "unknowntype" },
        points = schema.takeIf { it.hasPoints() }?.points,
    )

    private fun status(status: Collections.CollectionStatus): CollectionStatus = when (status) {
        Collections.CollectionStatus.Green -> CollectionStatus.GREEN
        Collections.CollectionStatus.Yellow -> CollectionStatus.YELLOW
        Collections.CollectionStatus.Red -> CollectionStatus.RED
        Collections.CollectionStatus.Grey -> CollectionStatus.GREY
        else -> CollectionStatus.UNKNOWN
    }

    private fun vectorsConfig(config: VectorsConfig): Collections.VectorsConfig =
        Collections.VectorsConfig.newBuilder().apply {
            when (config) {
                is VectorsConfig.Single -> params = vectorParams(config.params)
                is VectorsConfig.Named -> paramsMap = Collections.VectorParamsMap.newBuilder()
                    .putAllMap(config.vectors.mapValues { (_, params) -> vectorParams(params) })
                    .build()
            }
        }.build()

    private fun vectorsConfigToModel(config: Collections.VectorsConfig): VectorsConfig? =
        when (config.configCase) {
            Collections.VectorsConfig.ConfigCase.PARAMS -> VectorsConfig.Single(vectorParamsToModel(config.params))
            Collections.VectorsConfig.ConfigCase.PARAMS_MAP -> VectorsConfig.Named(
                config.paramsMap.mapMap.mapValues { (_, params) -> vectorParamsToModel(params) },
            )
            Collections.VectorsConfig.ConfigCase.CONFIG_NOT_SET, null -> null
        }

    private fun vectorParams(params: VectorParams): Collections.VectorParams =
        Collections.VectorParams.newBuilder().apply {
            size = params.size
            distance = distance(params.distance)
            params.onDisk?.let { onDisk = it }
            params.datatype?.let { datatype = datatype(it) }
            params.hnswConfig?.let { hnswConfig = hnswConfig(it) }
            params.multivectorConfig?.let { multivectorConfig = multiVectorConfig(it) }
        }.build()

    private fun vectorParamsToModel(params: Collections.VectorParams): VectorParams = VectorParams(
        size = params.size,
        distance = distanceToModel(params.distance),
        onDisk = params.takeIf { it.hasOnDisk() }?.onDisk,
        datatype = params.takeIf { it.hasDatatype() }?.datatype?.let(::datatypeToModel),
        hnswConfig = params.takeIf { it.hasHnswConfig() }?.hnswConfig?.let(::hnswConfigToModel),
        multivectorConfig = params.takeIf { it.hasMultivectorConfig() }?.let {
            MultiVectorConfig(MultiVectorComparator.MAX_SIM)
        },
    )

    private fun sparseVectorConfig(vectors: Map<String, SparseVectorParams>): Collections.SparseVectorConfig =
        Collections.SparseVectorConfig.newBuilder()
            .putAllMap(
                vectors.mapValues { (_, params) ->
                    Collections.SparseVectorParams.newBuilder().apply {
                        params.modifier?.let { modifier = modifier(it) }
                    }.build()
                },
            )
            .build()

    private fun sparseVectorParamsToModel(params: Collections.SparseVectorParams): SparseVectorParams =
        SparseVectorParams(
            modifier = params.takeIf { it.hasModifier() }?.modifier?.let {
                if (it == Collections.Modifier.Idf) Modifier.IDF else Modifier.NONE
            },
        )

    private fun modifier(modifier: Modifier): Collections.Modifier = when (modifier) {
        Modifier.NONE -> Collections.Modifier.None
        Modifier.IDF -> Collections.Modifier.Idf
    }

    private fun multiVectorConfig(config: MultiVectorConfig): Collections.MultiVectorConfig =
        Collections.MultiVectorConfig.newBuilder()
            .setComparator(
                when (config.comparator) {
                    MultiVectorComparator.MAX_SIM -> Collections.MultiVectorComparator.MaxSim
                },
            )
            .build()

    private fun distance(distance: Distance): Collections.Distance = when (distance) {
        Distance.COSINE -> Collections.Distance.Cosine
        Distance.DOT -> Collections.Distance.Dot
        Distance.EUCLID -> Collections.Distance.Euclid
        Distance.MANHATTAN -> Collections.Distance.Manhattan
    }

    private fun distanceToModel(distance: Collections.Distance): Distance = when (distance) {
        Collections.Distance.Cosine -> Distance.COSINE
        Collections.Distance.Dot -> Distance.DOT
        Collections.Distance.Euclid -> Distance.EUCLID
        Collections.Distance.Manhattan -> Distance.MANHATTAN
        // A distance this client does not know reads as cosine rather than failing the response. The
        // model has no unknown variant, and the field is a read-back of a collection someone else made.
        else -> Distance.COSINE
    }

    private fun datatype(datatype: VectorDatatype): Collections.Datatype = when (datatype) {
        VectorDatatype.FLOAT32 -> Collections.Datatype.Float32
        VectorDatatype.UINT8 -> Collections.Datatype.Uint8
        VectorDatatype.FLOAT16 -> Collections.Datatype.Float16
    }

    private fun datatypeToModel(datatype: Collections.Datatype): VectorDatatype? = when (datatype) {
        Collections.Datatype.Float32 -> VectorDatatype.FLOAT32
        Collections.Datatype.Uint8 -> VectorDatatype.UINT8
        Collections.Datatype.Float16 -> VectorDatatype.FLOAT16
        else -> null
    }

    private fun hnswConfig(config: HnswConfig): Collections.HnswConfigDiff =
        Collections.HnswConfigDiff.newBuilder().apply {
            config.m?.let { m = it.toLong() }
            config.efConstruct?.let { efConstruct = it.toLong() }
            config.fullScanThreshold?.let { fullScanThreshold = it.toLong() }
            config.maxIndexingThreads?.let { maxIndexingThreads = it.toLong() }
            config.onDisk?.let { onDisk = it }
            config.payloadM?.let { payloadM = it.toLong() }
        }.build()

    private fun hnswConfigToModel(config: Collections.HnswConfigDiff): HnswConfig = HnswConfig(
        m = config.takeIf { it.hasM() }?.m?.toInt(),
        efConstruct = config.takeIf { it.hasEfConstruct() }?.efConstruct?.toInt(),
        fullScanThreshold = config.takeIf { it.hasFullScanThreshold() }?.fullScanThreshold?.toInt(),
        maxIndexingThreads = config.takeIf { it.hasMaxIndexingThreads() }?.maxIndexingThreads?.toInt(),
        onDisk = config.takeIf { it.hasOnDisk() }?.onDisk,
        payloadM = config.takeIf { it.hasPayloadM() }?.payloadM?.toInt(),
    )

    private fun optimizersConfig(config: OptimizersConfig): Collections.OptimizersConfigDiff =
        Collections.OptimizersConfigDiff.newBuilder().apply {
            config.deletedThreshold?.let { deletedThreshold = it }
            config.vacuumMinVectorNumber?.let { vacuumMinVectorNumber = it.toLong() }
            config.defaultSegmentNumber?.let { defaultSegmentNumber = it.toLong() }
            config.maxSegmentSize?.let { maxSegmentSize = it.toLong() }
            config.memmapThreshold?.let { memmapThreshold = it.toLong() }
            config.indexingThreshold?.let { indexingThreshold = it.toLong() }
            config.flushIntervalSec?.let { flushIntervalSec = it.toLong() }
            config.maxOptimizationThreads?.let {
                maxOptimizationThreads = Collections.MaxOptimizationThreads.newBuilder().setValue(it.toLong()).build()
            }
        }.build()

    /**
     * Every field is optional on both sides, so a limit the caller did not set stays unset here rather
     * than being sent as a zero — which for a rate limit would mean "no requests per minute".
     */
    private fun strictModeConfig(config: StrictModeConfig): Collections.StrictModeConfig =
        Collections.StrictModeConfig.newBuilder().apply {
            config.enabled?.let { enabled = it }
            config.maxQueryLimit?.let { maxQueryLimit = it }
            config.maxTimeout?.let { maxTimeout = it }
            config.unindexedFilteringRetrieve?.let { unindexedFilteringRetrieve = it }
            config.unindexedFilteringUpdate?.let { unindexedFilteringUpdate = it }
            config.searchMaxHnswEf?.let { searchMaxHnswEf = it }
            config.searchAllowExact?.let { searchAllowExact = it }
            config.upsertMaxBatchSize?.let { upsertMaxBatchsize = it.toLong() }
            config.searchMaxBatchSize?.let { searchMaxBatchsize = it.toLong() }
            config.readRateLimit?.let { readRateLimit = it }
            config.writeRateLimit?.let { writeRateLimit = it }
            config.maxPointsCount?.let { maxPointsCount = it }
            config.filterMaxConditions?.let { filterMaxConditions = it.toLong() }
            config.maxDiskUsagePercent?.let { maxDiskUsagePercent = it }
            config.maxResidentMemoryPercent?.let { maxResidentMemoryPercent = it }
        }.build()

    private fun quantizationConfig(config: QuantizationConfig): Collections.QuantizationConfig =
        Collections.QuantizationConfig.newBuilder().apply {
            when (config) {
                is QuantizationConfig.Scalar -> scalar = scalarQuantization(config)
                is QuantizationConfig.Binary -> binary = binaryQuantization(config)
            }
        }.build()

    private fun quantizationConfigDiff(config: QuantizationConfig): Collections.QuantizationConfigDiff =
        Collections.QuantizationConfigDiff.newBuilder().apply {
            when (config) {
                is QuantizationConfig.Scalar -> scalar = scalarQuantization(config)
                is QuantizationConfig.Binary -> binary = binaryQuantization(config)
            }
        }.build()

    private fun scalarQuantization(config: QuantizationConfig.Scalar): Collections.ScalarQuantization =
        Collections.ScalarQuantization.newBuilder().apply {
            // The model has one scalar type, the same `int8` the REST serializer writes.
            type = Collections.QuantizationType.Int8
            config.quantile?.let { quantile = it }
            config.alwaysRam?.let { alwaysRam = it }
        }.build()

    private fun binaryQuantization(config: QuantizationConfig.Binary): Collections.BinaryQuantization =
        Collections.BinaryQuantization.newBuilder().apply {
            config.alwaysRam?.let { alwaysRam = it }
        }.build()
}
