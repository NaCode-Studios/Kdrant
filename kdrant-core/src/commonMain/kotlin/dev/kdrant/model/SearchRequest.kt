package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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

    /** Restrict the search to the shards holding this key. `null` searches every shard. */
    @SerialName("shard_key")
    public val shardKey: ShardKey? = null,

    /**
     * Stable token sent as the `X-Qdrant-Route-Affinity` header rather than in the body, which is why it
     * is [Transient]. See [dev.kdrant.dsl.SearchBuilder.routeAffinity].
     */
    @Transient
    public val routeAffinity: String? = null,
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

    /** Sparse-vector IDF statistics computed only over [IdfParams.corpus]. */
    @SerialName("idf")
    public val idf: IdfParams? = null,

    /** How a quantized collection is read. See [QuantizationSearchParams]. */
    @SerialName("quantization")
    public val quantization: QuantizationSearchParams? = null,
)

/**
 * How a search reads a quantized collection.
 *
 * This is the half of quantization that decides recall, and leaving it unset is the mistake: a collection
 * quantized to a quarter of its size answers from the approximation unless something asks for the
 * originals, and a caller who never sets [rescore] has traded accuracy they did not choose to trade.
 * Qdrant decides for itself when it is unset, and its choice depends on where the vectors are stored.
 *
 * [oversampling] is the lever that makes rescoring worth it: preselect more candidates from the quantized
 * index, then rank that larger set against the originals. At `2.0` with a limit of 100, Qdrant scores 200
 * approximately and returns the best 100 exactly.
 */
@Serializable
public data class QuantizationSearchParams(
    /** Read the original vectors and ignore the quantized ones entirely. */
    @SerialName("ignore")
    public val ignore: Boolean? = null,

    /**
     * Re-score the top candidates against the original vectors.
     *
     * Costs a read of the originals, which is why it is a choice rather than a default: with the originals
     * on disk it is a disk read per candidate, and with them in RAM it is nearly free.
     */
    @SerialName("rescore")
    public val rescore: Boolean? = null,

    /** Candidates to preselect from the quantized index, as a multiple of the limit. */
    @SerialName("oversampling")
    public val oversampling: Double? = null,
) {
    init {
        oversampling?.let {
            require(it >= 1.0) {
                "oversampling must be >= 1.0, was $it. Below one it would preselect fewer candidates " +
                    "than the limit asks for, which cannot return a full page."
            }
        }
    }
}

/** A per-query population used to compute sparse-vector IDF statistics. */
@Serializable
public data class IdfParams(
    /** The corpus is independent from the retrieval filter and is usually broader. */
    @SerialName("corpus")
    public val corpus: Filter,
)
