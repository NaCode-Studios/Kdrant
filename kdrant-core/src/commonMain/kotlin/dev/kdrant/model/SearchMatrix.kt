package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for the distance-matrix analytics endpoints. Qdrant samples [sample] points and,
 * for each, finds its [limit] nearest neighbours among the sample, optionally restricted by [filter]
 * and computed on the [using] named vector.
 */
@Serializable
public data class SearchMatrixRequest(
    @SerialName("filter")
    public val filter: Filter? = null,
    @SerialName("sample")
    public val sample: Int? = null,
    @SerialName("limit")
    public val limit: Int? = null,
    @SerialName("using")
    public val using: String? = null,
)

/** A single edge of the distance matrix: point [a] to point [b] with their similarity [score]. */
@Serializable
public data class SearchMatrixPair(
    @SerialName("a")
    public val a: PointId,
    @SerialName("b")
    public val b: PointId,
    @SerialName("score")
    public val score: Float,
)

/** Distance matrix in pairs form: an explicit list of scored point pairs. */
@Serializable
public data class SearchMatrixPairs(
    @SerialName("pairs")
    public val pairs: List<SearchMatrixPair>,
)

/**
 * Distance matrix in sparse coordinate (COO) form: [scores]`[i]` is the score between the points at
 * [ids]`[offsetsRow[i]]` and [ids]`[offsetsCol[i]]`. Compact for feeding clustering / dimensionality
 * reduction.
 */
@Serializable
public data class SearchMatrixOffsets(
    @SerialName("offsets_row")
    public val offsetsRow: List<Long>,
    @SerialName("offsets_col")
    public val offsetsCol: List<Long>,
    @SerialName("scores")
    public val scores: List<Float>,
    @SerialName("ids")
    public val ids: List<PointId>,
)
