package dev.kdrant.internal

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.FilterBuilder
import dev.kdrant.dsl.ScrollBuilder
import dev.kdrant.dsl.SearchBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.PointId
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
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

    override fun close() {
        transport.close()
    }
}
