package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A group of hits sharing the same `group_by` value, returned by `searchGroups`. */
@Serializable
public data class PointGroup(
    /** The shared value of the group-by field (a string or number). */
    @SerialName("id")
    public val id: JsonPrimitive,

    /** The scored points in this group. */
    @SerialName("hits")
    public val hits: List<ScoredPoint>,

    /** A point looked up by the group id, when a with-lookup option is used. */
    @SerialName("lookup")
    public val lookup: Record? = null,
)

/** Request body for `POST /collections/{name}/points/query/groups`. */
@Serializable
public data class SearchGroupsRequest(
    @SerialName("group_by")
    public val groupBy: String,

    @SerialName("group_size")
    public val groupSize: Int? = null,

    @SerialName("limit")
    public val limit: Int? = null,

    @SerialName("prefetch")
    public val prefetch: List<Prefetch>? = null,

    @SerialName("query")
    public val query: QueryInterface? = null,

    @SerialName("using")
    public val using: String? = null,

    @SerialName("filter")
    public val filter: Filter? = null,

    @SerialName("params")
    public val params: SearchParams? = null,

    @SerialName("score_threshold")
    public val scoreThreshold: Double? = null,

    @SerialName("with_payload")
    public val withPayload: WithPayload? = null,

    @SerialName("with_vector")
    public val withVector: Boolean? = null,

    @SerialName("lookup_from")
    public val lookupFrom: LookupLocation? = null,
)
