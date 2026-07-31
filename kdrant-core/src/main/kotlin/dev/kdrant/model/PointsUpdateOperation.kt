package dev.kdrant.model

/**
 * One step of a [batchUpdate][dev.kdrant.QdrantClient.batchUpdate]: a point, vector or payload change
 * that Qdrant applies as part of a single ordered request.
 *
 * The batch is **ordered but not transactional**. Qdrant applies the operations in the order given, so
 * a later step sees the effect of an earlier one, but there is no all-or-nothing rollback across the
 * batch: if step three fails, steps one and two stay applied.
 */
public sealed interface PointsUpdateOperation {

    /** Insert or replace [points]. */
    public data class Upsert(public val points: List<PointStruct>) : PointsUpdateOperation

    /** Delete the selected points. */
    public data class Delete(public val selector: DeleteSelector) : PointsUpdateOperation

    /** Merge [payload] into the selected points' payload, optionally under the nested path [key]. */
    public data class SetPayload(
        public val payload: Payload,
        public val selector: DeleteSelector,
        public val key: String? = null,
    ) : PointsUpdateOperation

    /** Replace the selected points' payload with [payload]. */
    public data class OverwritePayload(
        public val payload: Payload,
        public val selector: DeleteSelector,
    ) : PointsUpdateOperation

    /** Delete [keys] from the selected points' payload. */
    public data class DeletePayload(
        public val keys: List<String>,
        public val selector: DeleteSelector,
    ) : PointsUpdateOperation

    /** Clear all payload from the selected points. */
    public data class ClearPayload(public val selector: DeleteSelector) : PointsUpdateOperation

    /** Update the vectors of existing points, keeping their payload. */
    public data class UpdateVectors(public val points: List<PointVectors>) : PointsUpdateOperation

    /** Delete the named [vectors] from the selected points. */
    public data class DeleteVectors(
        public val vectors: List<String>,
        public val selector: DeleteSelector,
    ) : PointsUpdateOperation
}
