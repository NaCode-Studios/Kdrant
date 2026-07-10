package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for `POST /collections/{name}/points/query` (nearest-vector search). */
@Serializable
public data class SearchRequest(
    @SerialName("query")
    public val query: List<Float>,

    /** Name of the vector to search when the collection has named vectors. */
    @SerialName("using")
    public val using: String? = null,

    @SerialName("filter")
    public val filter: Filter? = null,

    @SerialName("limit")
    public val limit: Int,

    @SerialName("offset")
    public val offset: Int? = null,

    @SerialName("with_payload")
    public val withPayload: WithPayload? = null,

    @SerialName("with_vector")
    public val withVector: Boolean? = null,

    @SerialName("score_threshold")
    public val scoreThreshold: Double? = null,

    @SerialName("params")
    public val params: SearchParams? = null,
)

/** Fine-tuning for a search's accuracy/speed trade-off. */
@Serializable
public data class SearchParams(
    /** Size of the HNSW candidate list; higher is more accurate but slower. */
    @SerialName("hnsw_ef")
    public val hnswEf: Int? = null,

    /** Bypass the ANN index and search exactly. */
    @SerialName("exact")
    public val exact: Boolean? = null,

    /** Search only already-indexed segments. */
    @SerialName("indexed_only")
    public val indexedOnly: Boolean? = null,
)
