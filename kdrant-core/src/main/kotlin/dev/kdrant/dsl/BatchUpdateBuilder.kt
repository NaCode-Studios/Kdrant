package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Payload
import dev.kdrant.model.PointId
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation

/**
 * DSL for `batchUpdate`: accumulate point, vector and payload operations that Qdrant applies in the
 * order they are added.
 *
 * ```kotlin
 * qdrant.batchUpdate("docs", wait = true) {
 *     upsert { point(1) { vector(0.1f, 0.2f); payload("lang" to "en") } }
 *     setPayload(payloadOf("reviewed" to true), byId(1))
 *     delete(byFilter { must { "stale" eq true } })
 * }
 * ```
 */
@KdrantDsl
public class BatchUpdateBuilder {
    private val operations: MutableList<PointsUpdateOperation> = mutableListOf()

    /** Insert or replace points, using the same DSL as `upsert`. */
    public fun upsert(configure: UpsertBuilder.() -> Unit) {
        operations += PointsUpdateOperation.Upsert(UpsertBuilder().apply(configure).build())
    }

    /** Delete the selected points. */
    public fun delete(selector: DeleteSelector) {
        operations += PointsUpdateOperation.Delete(selector)
    }

    /** Merge [payload] into the selected points' payload, optionally under the nested path [key]. */
    public fun setPayload(payload: Payload, selector: DeleteSelector, key: String? = null) {
        operations += PointsUpdateOperation.SetPayload(payload, selector, key)
    }

    /** Replace the selected points' payload with [payload]. */
    public fun overwritePayload(payload: Payload, selector: DeleteSelector) {
        operations += PointsUpdateOperation.OverwritePayload(payload, selector)
    }

    /** Delete [keys] from the selected points' payload. */
    public fun deletePayload(keys: List<String>, selector: DeleteSelector) {
        require(keys.isNotEmpty()) { "deletePayload needs at least one key" }
        operations += PointsUpdateOperation.DeletePayload(keys, selector)
    }

    /** Clear all payload from the selected points. */
    public fun clearPayload(selector: DeleteSelector) {
        operations += PointsUpdateOperation.ClearPayload(selector)
    }

    /** Update the vectors of existing points, keeping their payload. */
    public fun updateVectors(points: List<PointVectors>) {
        require(points.isNotEmpty()) { "updateVectors needs at least one point" }
        operations += PointsUpdateOperation.UpdateVectors(points)
    }

    /** Delete the named [vectors] from the selected points. */
    public fun deleteVectors(vectors: List<String>, selector: DeleteSelector) {
        require(vectors.isNotEmpty()) { "deleteVectors needs at least one vector name" }
        operations += PointsUpdateOperation.DeleteVectors(vectors, selector)
    }

    /** Select points by id, e.g. `setPayload(p, byId(1, 2))`. */
    public fun byId(vararg ids: PointId): DeleteSelector = byId(ids.toList())

    /** Select points by id. */
    public fun byId(ids: List<PointId>): DeleteSelector {
        require(ids.isNotEmpty()) { "byId needs at least one id" }
        return DeleteSelector.Ids(ids)
    }

    /** Select points by id, e.g. `byId(1, 2)` for numeric ids. */
    public fun byId(vararg ids: Long): DeleteSelector = byId(ids.map(PointId::num))

    /**
     * Select points by filter. An empty filter is rejected: on `delete` or `clearPayload` it would
     * match every point in the collection.
     */
    public fun byFilter(configure: FilterBuilder.() -> Unit): DeleteSelector {
        val filter = FilterBuilder().apply(configure).build()
        require(filter.hasConditions()) {
            "byFilter requires at least one condition; an empty filter would match every point"
        }
        return DeleteSelector.ByFilter(filter)
    }

    internal fun build(): List<PointsUpdateOperation> {
        require(operations.isNotEmpty()) { "batchUpdate requires at least one operation" }
        return operations.toList()
    }
}
