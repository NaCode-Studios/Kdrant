package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `POST /collections/{name}/points/query`.
 *
 * A request carries a [query] (what to do with the candidates), optional [prefetch] sub-requests
 * (fetched first, then combined/reranked by [query] — the basis of hybrid search), or both.
 */
@Serializable
public data class SearchRequest(
    /** Sub-requests fetched first; [query] then combines or reranks their results. */
    @SerialName("prefetch")
    public val prefetch: List<Prefetch>? = null,

    /** What to do with the candidates: nearest vector/id, fusion, order-by, sample, ... */
    @SerialName("query")
    public val query: QueryInterface? = null,

    /** Name of the vector to search when the collection has named vectors. */
    @SerialName("using")
    public val using: String? = null,

    @SerialName("filter")
    public val filter: Filter? = null,

    @SerialName("limit")
    public val limit: Int? = null,

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

    /** Where to look up vectors for a query-by-id (another collection and optional vector name). */
    @SerialName("lookup_from")
    public val lookupFrom: LookupLocation? = null,
)

/**
 * A prefetch sub-request: candidates fetched first, then combined or reranked by the outer
 * [SearchRequest.query]. Prefetches nest recursively for multi-stage retrieval.
 */
@Serializable
public data class Prefetch(
    @SerialName("prefetch")
    public val prefetch: List<Prefetch>? = null,

    @SerialName("query")
    public val query: QueryInterface? = null,

    @SerialName("using")
    public val using: String? = null,

    @SerialName("filter")
    public val filter: Filter? = null,

    @SerialName("limit")
    public val limit: Int? = null,

    @SerialName("score_threshold")
    public val scoreThreshold: Double? = null,

    @SerialName("params")
    public val params: SearchParams? = null,

    @SerialName("lookup_from")
    public val lookupFrom: LookupLocation? = null,
)

/** Where to look up a point's vector for [SearchRequest.lookupFrom]: a collection and optional vector name. */
@Serializable
public data class LookupLocation(
    @SerialName("collection")
    public val collection: String,

    @SerialName("vector")
    public val vector: String? = null,
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
