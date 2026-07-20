package dev.kdrant.transport

import dev.kdrant.model.AliasDescription
import dev.kdrant.model.AliasOperation
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.FacetHit
import dev.kdrant.model.Filter
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.model.WithPayload
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-protocol seam. Concrete engines (the REST/Ktor engine lives in `kdrant-transport-rest`;
 * a gRPC engine can be added later) implement this interface so the public models and DSL in
 * `kdrant-core` stay independent of the wire protocol.
 *
 * Implementations receive already-built request models (the DSL lives above this seam) and must
 * translate transport failures into a [dev.kdrant.KdrantException], always re-throwing
 * [kotlinx.coroutines.CancellationException].
 */
public interface QdrantTransport : AutoCloseable {

    /** Create a collection (`PUT /collections/{name}`). */
    public suspend fun createCollection(name: String, request: CreateCollectionRequest)

    /** Update an existing collection's config (`PATCH /collections/{name}`). */
    public suspend fun updateCollection(name: String, request: UpdateCollectionRequest)

    /** Delete a collection (`DELETE /collections/{name}`). */
    public suspend fun deleteCollection(name: String)

    /**
     * Upsert points (`PUT /collections/{name}/points`). Implementations are responsible for
     * any transport-specific batching (e.g. the REST engine splits [points] to stay under the
     * 32 MiB payload cap). [wait] maps to `?wait=`.
     */
    public suspend fun upsert(name: String, points: List<PointStruct>, wait: Boolean)

    /** Nearest-vector search (`POST /collections/{name}/points/query`). */
    public suspend fun query(name: String, request: SearchRequest): List<ScoredPoint>

    /** Batch nearest-vector search (`POST /collections/{name}/points/query/batch`). */
    public suspend fun queryBatch(name: String, requests: List<SearchRequest>): List<List<ScoredPoint>>

    /** Grouped nearest-vector search (`POST /collections/{name}/points/query/groups`). */
    public suspend fun queryGroups(name: String, request: SearchGroupsRequest): List<PointGroup>

    /** Create a payload field index (`PUT /collections/{name}/index`). */
    public suspend fun createPayloadIndex(name: String, field: String, schema: PayloadSchemaType, wait: Boolean)

    /** Delete a payload field index (`DELETE /collections/{name}/index/{field}`). */
    public suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean)

    /** Merge [payload] into the selected points (`POST /collections/{name}/points/payload`). */
    public suspend fun setPayload(name: String, payload: Payload, selector: DeleteSelector, key: String?, wait: Boolean)

    /** Replace the payload of the selected points (`PUT /collections/{name}/points/payload`). */
    public suspend fun overwritePayload(name: String, payload: Payload, selector: DeleteSelector, wait: Boolean)

    /** Delete [keys] from the selected points' payload (`POST /collections/{name}/points/payload/delete`). */
    public suspend fun deletePayload(name: String, keys: List<String>, selector: DeleteSelector, wait: Boolean)

    /** Clear all payload from the selected points (`POST /collections/{name}/points/payload/clear`). */
    public suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean)

    /** Update the vectors of existing points (`PUT /collections/{name}/points/vectors`). */
    public suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean)

    /** Delete named vectors from the selected points (`POST /collections/{name}/points/vectors/delete`). */
    public suspend fun deleteVectors(name: String, vectors: List<String>, selector: DeleteSelector, wait: Boolean)

    /** Fetch a single page of points (`POST /collections/{name}/points/scroll`). */
    public suspend fun scroll(name: String, request: ScrollRequest): ScrollPage

    /** Delete points by id or by filter (`POST /collections/{name}/points/delete`). */
    public suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean)

    /** Whether a collection exists (`GET /collections/{name}/exists`). */
    public suspend fun collectionExists(name: String): Boolean

    /** Collection status and counts (`GET /collections/{name}`). */
    public suspend fun getCollection(name: String): CollectionInfo

    /** Count points, optionally filtered (`POST /collections/{name}/points/count`). */
    public suspend fun count(name: String, filter: Filter?, exact: Boolean): Long

    /** Retrieve points by id (`POST /collections/{name}/points`). */
    public suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload?,
        withVector: Boolean?,
    ): List<Record>

    // --- Aliases (M19) ---

    /** Apply alias changes atomically (`POST /collections/aliases`); [timeout] is in seconds. */
    public suspend fun updateAliases(operations: List<AliasOperation>, timeout: Int?)

    /** List every alias across all collections (`GET /aliases`). */
    public suspend fun listAliases(): List<AliasDescription>

    /** List the aliases pointing at one collection (`GET /collections/{name}/aliases`). */
    public suspend fun listCollectionAliases(name: String): List<AliasDescription>

    // --- Service & health (M19) ---

    /** Whether the node is healthy (`GET /healthz`); `false` on a non-2xx probe response. */
    public suspend fun healthz(): Boolean

    /** Whether the node is ready to serve (`GET /readyz`); `false` on a non-2xx probe response. */
    public suspend fun readyz(): Boolean

    /** Whether the node is alive (`GET /livez`); `false` on a non-2xx probe response. */
    public suspend fun livez(): Boolean

    /** List all collection names (`GET /collections`). */
    public suspend fun listCollections(): List<CollectionDescription>

    /** Telemetry data as a raw JSON object (`GET /telemetry`). */
    public suspend fun telemetry(): JsonObject

    /** Prometheus metrics in text exposition format (`GET /metrics`). */
    public suspend fun metrics(): String

    /** Detected performance issues as raw JSON (`GET /issues`). */
    public suspend fun listIssues(): JsonElement

    /** Clear the collected issues (`DELETE /issues`). */
    public suspend fun clearIssues()

    // --- Analytics (M19) ---

    /** Facet a payload [key], counting distinct values (`POST /collections/{name}/facet`). */
    public suspend fun facet(name: String, key: String, filter: Filter?, limit: Int?, exact: Boolean): List<FacetHit>

    /** Distance matrix in pairs form (`POST /collections/{name}/points/search/matrix/pairs`). */
    public suspend fun searchMatrixPairs(name: String, request: SearchMatrixRequest): SearchMatrixPairs

    /** Distance matrix in offsets form (`POST /collections/{name}/points/search/matrix/offsets`). */
    public suspend fun searchMatrixOffsets(name: String, request: SearchMatrixRequest): SearchMatrixOffsets
}
