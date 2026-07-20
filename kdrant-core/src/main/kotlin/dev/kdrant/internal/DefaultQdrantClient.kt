package dev.kdrant.internal

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.BatchSearchBuilder
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.FilterBuilder
import dev.kdrant.dsl.ScrollBuilder
import dev.kdrant.dsl.SearchBuilder
import dev.kdrant.dsl.UpdateCollectionBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointVectors
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
            var offset: PointId? = null
            while (true) {
                val page = transport.scroll(name, builder.build(offset))
                page.points.forEach { emit(it) }
                offset = page.nextPageOffset ?: break
            }
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
        val built = FilterBuilder().apply(filter).build()
        require(
            !built.must.isNullOrEmpty() || !built.should.isNullOrEmpty() ||
                !built.mustNot.isNullOrEmpty() || built.minShould != null,
        ) {
            "delete-by-filter requires at least one condition; an empty filter would match every point"
        }
        transport.delete(name, DeleteSelector.ByFilter(built), wait)
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

    override suspend fun createPayloadIndex(name: String, field: String, schema: PayloadSchemaType, wait: Boolean): Unit =
        transport.createPayloadIndex(name, field, schema, wait)

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

    override suspend fun deleteVectors(
        name: String,
        vectors: List<String>,
        selector: DeleteSelector,
        wait: Boolean,
    ): Unit = transport.deleteVectors(name, vectors, selector, wait)

    override fun close() {
        transport.close()
    }
}
