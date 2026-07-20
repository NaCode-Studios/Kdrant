package dev.kdrant

import dev.kdrant.dsl.BatchSearchBuilder
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.FilterBuilder
import dev.kdrant.dsl.ScrollBuilder
import dev.kdrant.dsl.SearchBuilder
import dev.kdrant.dsl.SearchMatrixBuilder
import dev.kdrant.dsl.UpdateAliasesBuilder
import dev.kdrant.dsl.UpdateCollectionBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.internal.DefaultQdrantClient
import dev.kdrant.model.AliasDescription
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
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
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SnapshotPriority
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
     * Update an existing collection's config (optimizers, HNSW, quantization).
     *
     * ```kotlin
     * qdrant.updateCollection("docs") {
     *     optimizers = OptimizersConfig(indexingThreshold = 20_000)
     *     quantization = QuantizationConfig.Scalar(quantile = 0.99f)
     * }
     * ```
     */
    public suspend fun updateCollection(name: String, configure: UpdateCollectionBuilder.() -> Unit)

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
     * Upsert points streamed from a [Flow] — ingest a large or unbounded source without materializing
     * it all in memory. The engine chunks the flow to stay under the request-size cap; as with the DSL
     * `upsert`, the chunks are applied sequentially and are **not** atomic across chunk boundaries.
     *
     * ```kotlin
     * qdrant.upsert("docs", embeddings.map { (id, v) -> PointStruct(PointId.num(id), VectorData.Dense(v)) })
     * ```
     */
    public suspend fun upsert(name: String, points: Flow<PointStruct>, wait: Boolean = false)

    /** Upsert points from a lazy [Sequence], chunked by the engine (see the [Flow] overload). */
    public suspend fun upsert(name: String, points: Sequence<PointStruct>, wait: Boolean = false)

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

    /**
     * Create a payload field index so filtering on [field] scales. Without an index, filters do a
     * full scan.
     *
     * ```kotlin
     * qdrant.createPayloadIndex("docs", "lang", PayloadSchemaType.KEYWORD)
     * ```
     */
    public suspend fun createPayloadIndex(
        name: String,
        field: String,
        schema: PayloadSchemaType,
        wait: Boolean = false,
    )

    /** Delete a payload field index. */
    public suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean = false)

    /**
     * Merge [payload] into the selected points' payload (existing keys are kept). Select the points
     * with `DeleteSelector.Ids(...)` or `DeleteSelector.ByFilter(filter { ... })`.
     *
     * @param key an optional payload path to assign under (nested set).
     */
    public suspend fun setPayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        key: String? = null,
        wait: Boolean = false,
    )

    /** Replace the selected points' payload with [payload]. */
    public suspend fun overwritePayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        wait: Boolean = false,
    )

    /** Delete [keys] from the selected points' payload. */
    public suspend fun deletePayload(
        name: String,
        keys: List<String>,
        selector: DeleteSelector,
        wait: Boolean = false,
    )

    /** Clear all payload from the selected points. */
    public suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean = false)

    /**
     * Update the vectors of existing points, keeping their payload.
     *
     * ```kotlin
     * qdrant.updateVectors("docs", listOf(PointVectors(PointId.num(1), VectorData.Dense(newEmbedding))))
     * ```
     */
    public suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean = false)

    /** Delete the named [vectors] from the selected points. */
    public suspend fun deleteVectors(
        name: String,
        vectors: List<String>,
        selector: DeleteSelector,
        wait: Boolean = false,
    )

    /**
     * Apply alias changes as one atomic batch — the primitive behind zero-downtime reindexing.
     *
     * ```kotlin
     * qdrant.updateAliases {
     *     deleteAlias("docs")
     *     createAlias(collection = "docs-v2", alias = "docs")
     * }
     * ```
     *
     * @param timeout optional server-side commit timeout, in seconds.
     * @throws IllegalArgumentException if no action is added.
     */
    public suspend fun updateAliases(timeout: Int? = null, configure: UpdateAliasesBuilder.() -> Unit)

    /** List every alias across all collections. */
    public suspend fun listAliases(): List<AliasDescription>

    /** List the aliases pointing at [name]. */
    public suspend fun listCollectionAliases(name: String): List<AliasDescription>

    /**
     * Kubernetes-style health probe: `true` when the node is healthy. Returns `false` (rather than
     * throwing) when the server responds not-healthy; still throws [KdrantException.Transport] if the
     * server can't be reached at all.
     */
    public suspend fun healthz(): Boolean

    /** Readiness probe: `true` when the node is ready to serve. See [healthz] for the error contract. */
    public suspend fun readyz(): Boolean

    /** Liveness probe: `true` when the node is alive. See [healthz] for the error contract. */
    public suspend fun livez(): Boolean

    /** List all collection names on the server. */
    public suspend fun listCollections(): List<CollectionDescription>

    /** The server's telemetry as a raw JSON object (shape is server-version-specific). */
    public suspend fun telemetry(): JsonObject

    /** The server's Prometheus metrics as a text-exposition-format string. */
    public suspend fun metrics(): String

    /** Detected performance issues as raw JSON (shape is server-version-specific). */
    public suspend fun listIssues(): JsonElement

    /** Clear the server's collected performance issues. */
    public suspend fun clearIssues()

    /**
     * Count the distinct values of a payload [key] among matching points (a payload histogram).
     *
     * ```kotlin
     * val langs = qdrant.facet("docs", key = "lang", limit = 20) { must { "year" gte 2020 } }
     * ```
     *
     * @param limit max number of distinct values to return.
     * @param exact an exact count (slower) vs the default approximate one.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     */
    public suspend fun facet(
        name: String,
        key: String,
        limit: Int? = null,
        exact: Boolean = false,
        filter: FilterBuilder.() -> Unit = {},
    ): List<FacetHit>

    /**
     * Sample points and return the pairwise distance matrix in pairs form (an explicit edge list).
     *
     * ```kotlin
     * val matrix = qdrant.searchMatrixPairs("docs") { sample = 100; limit = 5 }
     * ```
     *
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     */
    public suspend fun searchMatrixPairs(
        name: String,
        configure: SearchMatrixBuilder.() -> Unit = {},
    ): SearchMatrixPairs

    /** As [searchMatrixPairs] but in sparse-coordinate (offsets) form, compact for clustering input. */
    public suspend fun searchMatrixOffsets(
        name: String,
        configure: SearchMatrixBuilder.() -> Unit = {},
    ): SearchMatrixOffsets

    /**
     * Create a snapshot of the collection [name]; the returned [SnapshotDescription.name] identifies it
     * for later download or recover.
     *
     * @param wait if `true` (the default) return only once the snapshot exists; if `false` it is built in
     *   the background. Note snapshot `wait` defaults to `true` — the opposite of the mutation `wait` flags.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     */
    public suspend fun createSnapshot(name: String, wait: Boolean = true): SnapshotDescription

    /** List the collection [name]'s snapshots. */
    public suspend fun listSnapshots(name: String): List<SnapshotDescription>

    /** Delete the snapshot [snapshotName] of collection [name]. */
    public suspend fun deleteSnapshot(name: String, snapshotName: String, wait: Boolean = true)

    /**
     * Recover collection [name] from a snapshot at [location] — an `http(s)://` URL (e.g. another node's
     * snapshot) or a `file:///` path the server can read.
     *
     * @param priority which data wins if the collection has replicas (see [SnapshotPriority]).
     * @param checksum optional SHA-256 checksum verified before recovery.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     */
    public suspend fun recoverSnapshot(
        name: String,
        location: String,
        priority: SnapshotPriority? = null,
        checksum: String? = null,
        wait: Boolean = true,
    )

    /**
     * Stream a collection snapshot's bytes as a cold [Flow], to save a backup without buffering the
     * whole (potentially multi-GB) file in memory.
     *
     * ```kotlin
     * val snap = qdrant.createSnapshot("docs")
     * File("docs.snapshot").outputStream().use { out ->
     *     qdrant.downloadSnapshot("docs", snap.name).collect { out.write(it) }
     * }
     * ```
     */
    public fun downloadSnapshot(name: String, snapshotName: String): Flow<ByteArray>

    /**
     * Upload a snapshot file (streamed from [data]) and recover collection [name] from it.
     *
     * @param priority which data wins if the collection has replicas (see [SnapshotPriority]).
     * @param checksum optional SHA-256 checksum verified before recovery.
     */
    public suspend fun uploadSnapshot(
        name: String,
        data: Flow<ByteArray>,
        priority: SnapshotPriority? = null,
        checksum: String? = null,
        wait: Boolean = true,
    )

    /** Create a snapshot of the whole storage (all collections). See [createSnapshot] for the `wait` note. */
    public suspend fun createStorageSnapshot(wait: Boolean = true): SnapshotDescription

    /** List whole-storage snapshots. */
    public suspend fun listStorageSnapshots(): List<SnapshotDescription>

    /** Delete the whole-storage snapshot [snapshotName]. */
    public suspend fun deleteStorageSnapshot(snapshotName: String, wait: Boolean = true)

    /** Stream a whole-storage snapshot's bytes as a cold [Flow]. See [downloadSnapshot]. */
    public fun downloadStorageSnapshot(snapshotName: String): Flow<ByteArray>
}

/** Wraps a [QdrantTransport] into a [QdrantClient]. Used by transport factories. */
public fun QdrantClient(transport: QdrantTransport): QdrantClient = DefaultQdrantClient(transport)
