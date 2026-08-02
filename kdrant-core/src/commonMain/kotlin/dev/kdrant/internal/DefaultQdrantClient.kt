package dev.kdrant.internal

import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.dsl.BatchSearchBuilder
import dev.kdrant.dsl.BatchUpdateBuilder
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.FilterBuilder
import dev.kdrant.dsl.ScrollBuilder
import dev.kdrant.dsl.SearchBuilder
import dev.kdrant.dsl.SearchMatrixBuilder
import dev.kdrant.dsl.UpdateAliasesBuilder
import dev.kdrant.dsl.UpdateCollectionBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.dsl.hasConditions
import dev.kdrant.model.AliasDescription
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.CollectionClusterInfo
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.CreateShardKeyRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.FacetHit
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.ShardKey
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SnapshotPriority
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Protocol-independent [QdrantClient]: turns the ergonomic DSL into request models and
 * delegates the actual I/O to a [QdrantTransport]. Lives in core (no wire-protocol knowledge).
 */
internal class DefaultQdrantClient(
    private val transport: QdrantTransport,
) : QdrantClient {

    override suspend fun createCollection(
        name: String,
        configure: CreateCollectionBuilder.() -> Unit,
    ) {
        val request = CreateCollectionBuilder().apply(configure).build()
        transport.createCollection(name, request)
    }

    override suspend fun ensureCollection(name: String, configure: CreateCollectionBuilder.() -> Unit): Boolean {
        val request = CreateCollectionBuilder().apply(configure).build()
        if (!transport.collectionExists(name)) {
            try {
                transport.createCollection(name, request)
                return true
            } catch (_: KdrantException.AlreadyExists) {
                // Another process created it between the check and the create. That is the case this
                // operation exists to absorb, so fall through and verify what is there.
            }
        }
        verifyVectors(name, request, transport.getCollection(name))
        return false
    }

    /**
     * Compares only what the caller asked for — the dense vector names with their size and distance,
     * and the sparse vector names. Anything the server defaults (HNSW, optimizers, quantization) is
     * left alone, so a collection tuned after creation still passes.
     */
    private fun verifyVectors(name: String, requested: CreateCollectionRequest, actual: CollectionInfo) {
        val params = checkNotNull(actual.config?.params) {
            "collection '$name' exists but the server returned no config, so it could not be checked " +
                "against the requested one"
        }
        val expected = requested.vectors.asNamedMap()
        val found = params.vectors.asNamedMap()
        val problems = buildList {
            (expected.keys + found.keys).sorted().forEach { vectorName ->
                val label = if (vectorName.isEmpty()) "the anonymous vector" else "vector '$vectorName'"
                val want = expected[vectorName]
                val have = found[vectorName]
                when {
                    want == null -> add("$label exists but was not requested")
                    have == null -> add("$label was requested but does not exist")
                    want.size != have.size -> add("$label has size ${have.size}, expected ${want.size}")
                    want.distance != have.distance ->
                        add("$label uses ${have.distance}, expected ${want.distance}")
                }
            }
            val expectedSparse = requested.sparseVectors.orEmpty().keys
            val foundSparse = params.sparseVectors.orEmpty().keys
            (expectedSparse - foundSparse).sorted().forEach { add("sparse vector '$it' does not exist") }
            (foundSparse - expectedSparse).sorted().forEach { add("sparse vector '$it' was not requested") }
        }
        check(problems.isEmpty()) {
            "collection '$name' already exists and does not match the requested config: " +
                problems.joinToString("; ")
        }
    }

    /** Both vector shapes as one map; the anonymous single vector keys on the empty string. */
    private fun VectorsConfig?.asNamedMap(): Map<String, VectorParams> = when (this) {
        null -> emptyMap()
        is VectorsConfig.Single -> mapOf("" to params)
        is VectorsConfig.Named -> vectors
    }

    override suspend fun updateCollection(name: String, configure: UpdateCollectionBuilder.() -> Unit) {
        transport.updateCollection(name, UpdateCollectionBuilder().apply(configure).build())
    }

    override suspend fun deleteCollection(name: String) {
        transport.deleteCollection(name)
    }

    override suspend fun upsert(
        name: String,
        wait: Boolean,
        configure: UpsertBuilder.() -> Unit,
    ) {
        val points = UpsertBuilder().apply(configure).build()
        transport.upsert(name, points, wait)
    }

    override suspend fun upsert(name: String, points: Flow<PointStruct>, wait: Boolean) {
        transport.upsert(name, points, wait)
    }

    override suspend fun upsert(name: String, points: Sequence<PointStruct>, wait: Boolean) {
        transport.upsert(name, points.asFlow(), wait)
    }

    override suspend fun search(
        name: String,
        configure: SearchBuilder.() -> Unit,
    ): List<ScoredPoint> = transport.query(name, SearchBuilder().apply(configure).build())

    override suspend fun searchBatch(
        name: String,
        configure: BatchSearchBuilder.() -> Unit,
    ): List<List<ScoredPoint>> = transport.queryBatch(name, BatchSearchBuilder().apply(configure).build())

    override suspend fun searchGroups(
        name: String,
        groupBy: String,
        groupSize: Int?,
        limit: Int?,
        configure: SearchBuilder.() -> Unit,
    ): List<PointGroup> {
        val sr = SearchBuilder().apply(configure).build()
        return transport.queryGroups(
            name,
            SearchGroupsRequest(
                groupBy = groupBy,
                groupSize = groupSize,
                limit = limit,
                prefetch = sr.prefetch,
                query = sr.query,
                using = sr.using,
                filter = sr.filter,
                params = sr.params,
                scoreThreshold = sr.scoreThreshold,
                withPayload = sr.withPayload,
                withVector = sr.withVector,
                lookupFrom = sr.lookupFrom,
            ),
        )
    }

    override fun scroll(
        name: String,
        pageSize: Int,
        configure: ScrollBuilder.() -> Unit,
    ): Flow<Record> {
        require(pageSize > 0) { "pageSize must be > 0, was $pageSize" }
        return flow {
            val builder = ScrollBuilder(pageSize).apply(configure)
            if (builder.isOrdered) scrollOrdered(name, builder, pageSize) else scrollById(name, builder)
        }
    }

    private suspend fun FlowCollector<Record>.scrollById(name: String, builder: ScrollBuilder) {
        var offset: PointId? = builder.startAt
        while (true) {
            val page = transport.scroll(name, builder.build(offset))
            page.points.forEach { emit(it) }
            offset = page.nextPageOffset ?: break
        }
    }

    /**
     * Qdrant never returns a page cursor for an ordered scroll, so paging follows the order value
     * instead. `start_from` is inclusive and filters by value alone, so the next page repeats every
     * point sharing the boundary value: those ids are remembered (only those — the set stays bounded
     * by how many points share one value) and filtered out, which keeps each point emitted once.
     */
    private suspend fun FlowCollector<Record>.scrollOrdered(name: String, builder: ScrollBuilder, pageSize: Int) {
        var startFrom: JsonPrimitive? = null
        var seenAtBoundary: Set<PointId> = emptySet()
        while (true) {
            val page = transport.scroll(name, builder.build(offset = null, startFrom = startFrom))
            val fresh = page.points.filterNot { it.id in seenAtBoundary }
            fresh.forEach { emit(it) }
            if (page.points.size < pageSize) break

            val boundary = checkNotNull(page.points.last().orderValue) {
                "an ordered scroll of '$name' returned points without an order value, so it cannot be resumed"
            }
            check(fresh.isNotEmpty()) {
                "an ordered scroll of '$name' cannot advance: more than $pageSize points share the order " +
                    "value $boundary. Raise pageSize above that, or order by a key with fewer ties."
            }
            val atBoundary = page.points.filter { it.orderValue == boundary }.map { it.id }
            seenAtBoundary = if (boundary == startFrom) seenAtBoundary + atBoundary else atBoundary.toSet()
            startFrom = boundary
        }
    }

    override suspend fun delete(name: String, ids: List<PointId>, wait: Boolean) {
        require(ids.isNotEmpty()) { "delete(ids) needs at least one id" }
        transport.delete(name, DeleteSelector.Ids(ids), wait)
    }

    override suspend fun delete(
        name: String,
        wait: Boolean,
        filter: FilterBuilder.() -> Unit,
    ) {
        delete(name, DeleteSelector.ByFilter(FilterBuilder().apply(filter).build()), wait)
    }

    override suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean) {
        when (selector) {
            is DeleteSelector.Ids ->
                require(selector.ids.isNotEmpty()) { "delete(ids) needs at least one id" }
            is DeleteSelector.ByFilter ->
                require(selector.filter.hasConditions()) {
                    "delete-by-filter requires at least one condition; an empty filter would match every point"
                }
        }
        transport.delete(name, selector, wait)
    }

    override suspend fun collectionExists(name: String): Boolean =
        transport.collectionExists(name)

    override suspend fun getCollection(name: String): CollectionInfo =
        transport.getCollection(name)

    override suspend fun count(name: String, exact: Boolean): Long =
        transport.count(name, filter = null, exact = exact)

    override suspend fun count(name: String, exact: Boolean, filter: FilterBuilder.() -> Unit): Long =
        transport.count(name, FilterBuilder().apply(filter).build(), exact)

    override suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload?,
        withVector: Boolean?,
    ): List<Record> {
        require(ids.isNotEmpty()) { "retrieve needs at least one id" }
        return transport.retrieve(name, ids, withPayload, withVector)
    }

    override suspend fun createPayloadIndex(
        name: String,
        field: String,
        schema: PayloadSchemaType,
        wait: Boolean,
    ): Unit = transport.createPayloadIndex(name, field, schema, wait)

    override suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean): Unit =
        transport.deletePayloadIndex(name, field, wait)

    override suspend fun setPayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        key: String?,
        wait: Boolean,
    ): Unit = transport.setPayload(name, payload, selector, key, wait)

    override suspend fun overwritePayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = transport.overwritePayload(name, payload, selector, wait)

    override suspend fun deletePayload(
        name: String,
        keys: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = transport.deletePayload(name, keys, selector, wait)

    override suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean): Unit =
        transport.clearPayload(name, selector, wait)

    override suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean): Unit =
        transport.updateVectors(name, points, wait)

    override suspend fun batchUpdate(name: String, wait: Boolean, configure: BatchUpdateBuilder.() -> Unit) {
        transport.batchUpdate(name, BatchUpdateBuilder().apply(configure).build(), wait)
    }

    override suspend fun deleteVectors(
        name: String,
        vectors: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = transport.deleteVectors(name, vectors, selector, wait)

    override suspend fun updateAliases(timeout: Int?, configure: UpdateAliasesBuilder.() -> Unit) {
        val operations = UpdateAliasesBuilder().apply(configure).build()
        require(operations.isNotEmpty()) {
            "updateAliases needs at least one action (createAlias / deleteAlias / renameAlias)"
        }
        transport.updateAliases(operations, timeout)
    }

    override suspend fun collectionClusterInfo(name: String): CollectionClusterInfo =
        transport.collectionClusterInfo(name)

    override suspend fun updateCollectionCluster(name: String, operation: ClusterOperation, timeout: Int?) {
        transport.updateCollectionCluster(name, operation, timeout)
    }

    override suspend fun createShardKey(
        name: String,
        shardKey: ShardKey,
        shardsNumber: Int?,
        replicationFactor: Int?,
        placement: List<Long>?,
        timeout: Int?,
    ) {
        shardsNumber?.let { require(it > 0) { "shardsNumber must be > 0, was $it" } }
        replicationFactor?.let { require(it > 0) { "replicationFactor must be > 0, was $it" } }
        transport.createShardKey(
            name,
            CreateShardKeyRequest(shardKey, shardsNumber, replicationFactor, placement?.takeIf { it.isNotEmpty() }),
            timeout,
        )
    }

    override suspend fun deleteShardKey(name: String, shardKey: ShardKey, timeout: Int?) {
        transport.deleteShardKey(name, shardKey, timeout)
    }

    override suspend fun listAliases(): List<AliasDescription> = transport.listAliases()

    override suspend fun listCollectionAliases(name: String): List<AliasDescription> =
        transport.listCollectionAliases(name)

    override suspend fun healthz(): Boolean = transport.healthz()

    override suspend fun readyz(): Boolean = transport.readyz()

    override suspend fun livez(): Boolean = transport.livez()

    override suspend fun listCollections(): List<CollectionDescription> = transport.listCollections()

    override suspend fun telemetry(): JsonObject = transport.telemetry()

    override suspend fun metrics(): String = transport.metrics()

    override suspend fun listIssues(): JsonElement = transport.listIssues()

    override suspend fun clearIssues() {
        transport.clearIssues()
    }

    override suspend fun facet(
        name: String,
        key: String,
        limit: Int?,
        exact: Boolean,
        filter: FilterBuilder.() -> Unit,
    ): List<FacetHit> {
        limit?.let { require(it >= 1) { "facet 'limit' must be >= 1, was $it" } }
        val built = FilterBuilder().apply(filter).build()
        val effectiveFilter = built.takeIf { it.hasConditions() }
        return transport.facet(name, key, effectiveFilter, limit, exact)
    }

    override suspend fun searchMatrixPairs(
        name: String,
        configure: SearchMatrixBuilder.() -> Unit,
    ): SearchMatrixPairs = transport.searchMatrixPairs(name, SearchMatrixBuilder().apply(configure).build())

    override suspend fun searchMatrixOffsets(
        name: String,
        configure: SearchMatrixBuilder.() -> Unit,
    ): SearchMatrixOffsets = transport.searchMatrixOffsets(name, SearchMatrixBuilder().apply(configure).build())

    override suspend fun createSnapshot(name: String, wait: Boolean): SnapshotDescription =
        transport.createSnapshot(name, wait)

    override suspend fun listSnapshots(name: String): List<SnapshotDescription> = transport.listSnapshots(name)

    override suspend fun deleteSnapshot(name: String, snapshotName: String, wait: Boolean) {
        transport.deleteSnapshot(name, snapshotName, wait)
    }

    override suspend fun recoverSnapshot(
        name: String,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        require(location.isNotBlank()) {
            "recoverSnapshot needs a non-blank location (an http(s):// URL or file:/// path)"
        }
        transport.recoverSnapshot(name, location, priority, checksum, wait)
    }

    override fun downloadSnapshot(name: String, snapshotName: String): Flow<ByteArray> =
        transport.downloadSnapshot(name, snapshotName)

    override suspend fun uploadSnapshot(
        name: String,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        transport.uploadSnapshot(name, data, priority, checksum, wait)
    }

    override suspend fun createShardSnapshot(name: String, shardId: Int, wait: Boolean): SnapshotDescription {
        require(shardId >= 0) { "shardId must be >= 0, was $shardId" }
        return transport.createShardSnapshot(name, shardId, wait)
    }

    override suspend fun listShardSnapshots(name: String, shardId: Int): List<SnapshotDescription> =
        transport.listShardSnapshots(name, shardId)

    override suspend fun deleteShardSnapshot(name: String, shardId: Int, snapshotName: String, wait: Boolean) {
        transport.deleteShardSnapshot(name, shardId, snapshotName, wait)
    }

    override suspend fun recoverShardSnapshot(
        name: String,
        shardId: Int,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        transport.recoverShardSnapshot(name, shardId, location, priority, checksum, wait)
    }

    override fun downloadShardSnapshot(name: String, shardId: Int, snapshotName: String): Flow<ByteArray> =
        transport.downloadShardSnapshot(name, shardId, snapshotName)

    override suspend fun uploadShardSnapshot(
        name: String,
        shardId: Int,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        transport.uploadShardSnapshot(name, shardId, data, priority, checksum, wait)
    }

    override suspend fun createStorageSnapshot(wait: Boolean): SnapshotDescription =
        transport.createStorageSnapshot(wait)

    override suspend fun listStorageSnapshots(): List<SnapshotDescription> = transport.listStorageSnapshots()

    override suspend fun deleteStorageSnapshot(snapshotName: String, wait: Boolean) {
        transport.deleteStorageSnapshot(snapshotName, wait)
    }

    override fun downloadStorageSnapshot(snapshotName: String): Flow<ByteArray> =
        transport.downloadStorageSnapshot(snapshotName)

    override fun close() {
        transport.close()
    }
}
