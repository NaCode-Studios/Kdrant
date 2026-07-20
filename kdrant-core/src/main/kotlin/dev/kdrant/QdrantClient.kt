package dev.kdrant

import dev.kdrant.dsl.BatchSearchBuilder
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.FilterBuilder
import dev.kdrant.dsl.ScrollBuilder
import dev.kdrant.dsl.SearchBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.internal.DefaultQdrantClient
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import kotlinx.coroutines.flow.Flow

/**
 * The user-facing Kdrant API. Coroutine-first: every operation is a `suspend` function.
 *
 * Obtain an instance from a transport factory, e.g. `Kdrant(host, port) { }` in
 * `kdrant-transport-rest`. Implements [AutoCloseable] — use it with `use { }`.
 *
 * Every operation performs network I/O and may throw a [KdrantException].
 */
public interface QdrantClient : AutoCloseable {

    /**
     * Create a collection.
     *
     * ```kotlin
     * qdrant.createCollection("docs") {
     *     vector { size = 768; distance = Distance.COSINE }
     *     onDiskPayload = true
     * }
     * ```
     *
     * Creating a collection that already exists is rejected by the server, not treated as a no-op.
     *
     * @param name the collection name.
     * @param configure builds the collection settings (vectors, HNSW, on-disk payload, ...).
     * @throws KdrantException.InvalidRequest if the collection already exists or the settings are invalid.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun createCollection(name: String, configure: CreateCollectionBuilder.() -> Unit)

    /**
     * Delete a collection. Deleting a collection that does not exist is a no-op on the server.
     *
     * @param name the collection name.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun deleteCollection(name: String)

    /**
     * Upsert points into a collection.
     *
     * ```kotlin
     * qdrant.upsert("docs", wait = true) {
     *     point(id = 1) {
     *         vector(0.05f, 0.61f, 0.76f)
     *         payload("title" to "Intro", "lang" to "it")
     *     }
     * }
     * ```
     *
     * The transport may split a large batch into several sequential requests, which is **not
     * atomic**: if a later request fails, earlier points are already applied. Upsert is idempotent
     * per point id, so retrying the whole call is safe.
     *
     * @param name the collection name.
     * @param wait if true, return only once the change is applied; if false (default) it is queued.
     * @param configure builds the points to upsert.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     * @throws KdrantException.InvalidRequest if a point is malformed (e.g. wrong vector size).
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if a request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun upsert(name: String, wait: Boolean = false, configure: UpsertBuilder.() -> Unit)

    /**
     * Nearest-vector search.
     *
     * ```kotlin
     * val hits = qdrant.search("docs") {
     *     query(queryVector)
     *     limit = 5
     *     filter { must { "lang" eq "en" } }
     * }
     * ```
     *
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     * @throws KdrantException.InvalidRequest if the query is malformed (e.g. wrong vector size).
     */
    public suspend fun search(name: String, configure: SearchBuilder.() -> Unit): List<ScoredPoint>

    /**
     * Run several searches in a single request; returns the hits for each, in the order added.
     *
     * ```kotlin
     * val (a, b) = qdrant.searchBatch("docs") { search { query(v1) }; search { query(v2) } }
     * ```
     */
    public suspend fun searchBatch(
        name: String,
        configure: BatchSearchBuilder.() -> Unit,
    ): List<List<ScoredPoint>>

    /**
     * Grouped search: return hits grouped by the [groupBy] payload field.
     *
     * @param groupSize max hits per group. @param limit max number of groups.
     */
    public suspend fun searchGroups(
        name: String,
        groupBy: String,
        groupSize: Int? = null,
        limit: Int? = null,
        configure: SearchBuilder.() -> Unit,
    ): List<PointGroup>

    /**
     * Stream all points (optionally filtered) as a cold [Flow], transparently following the
     * server's pagination cursor. Cancellation and backpressure are cooperative.
     *
     * ```kotlin
     * qdrant.scroll("docs", pageSize = 256) { filter { must { "lang" eq "en" } } }
     *     .collect { record -> /* ... */ }
     * ```
     *
     * @param pageSize how many points to fetch per request.
     */
    public fun scroll(name: String, pageSize: Int = 64, configure: ScrollBuilder.() -> Unit = {}): Flow<Record>

    /**
     * Delete points by id.
     *
     * @param wait if true, return only once the change is applied.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     */
    public suspend fun delete(name: String, ids: List<PointId>, wait: Boolean = false)

    /**
     * Delete every point matching the given filter.
     *
     * ```kotlin
     * qdrant.delete("docs") { must { "lang" eq "en" } }
     * ```
     */
    public suspend fun delete(name: String, wait: Boolean = false, filter: FilterBuilder.() -> Unit)

    /**
     * Whether a collection exists. Returns `false` (not an error) for a missing collection.
     *
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun collectionExists(name: String): Boolean

    /**
     * Fetch a collection's status and point counts.
     *
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun getCollection(name: String): CollectionInfo

    /**
     * Count the points in a collection.
     *
     * @param exact an exact count (default) vs a faster approximate one.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun count(name: String, exact: Boolean = true): Long

    /**
     * Count the points in a collection that match a filter.
     *
     * ```kotlin
     * val n = qdrant.count("docs") { must { "lang" eq "en" } }
     * ```
     *
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     */
    public suspend fun count(name: String, exact: Boolean = true, filter: FilterBuilder.() -> Unit): Long

    /**
     * Retrieve points by id.
     *
     * @throws IllegalArgumentException if [ids] is empty.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload? = null,
        withVector: Boolean? = null,
    ): List<Record>
}

/** Wraps a [QdrantTransport] into a [QdrantClient]. Used by transport factories. */
public fun QdrantClient(transport: QdrantTransport): QdrantClient = DefaultQdrantClient(transport)
