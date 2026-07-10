package dev.kdrant.transport

import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.PointStruct
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchRequest

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

    /** Fetch a single page of points (`POST /collections/{name}/points/scroll`). */
    public suspend fun scroll(name: String, request: ScrollRequest): ScrollPage

    /** Delete points by id or by filter (`POST /collections/{name}/points/delete`). */
    public suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean)
}
