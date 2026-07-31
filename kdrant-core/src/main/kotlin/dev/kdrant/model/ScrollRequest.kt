package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Request body for a single `POST /collections/{name}/points/scroll` page. */
@Serializable
public data class ScrollRequest(
    @SerialName("filter")
    public val filter: Filter? = null,

    @SerialName("limit")
    public val limit: Int,

    /** Page cursor: the point id to start after. `null` for the first page. */
    @SerialName("offset")
    public val offset: PointId? = null,

    @SerialName("with_payload")
    public val withPayload: WithPayload? = null,

    @SerialName("with_vector")
    public val withVector: Boolean? = null,

    /**
     * Order the page by a payload key instead of by point id. Qdrant rejects an ordered scroll that
     * also carries an [offset] and never returns a [ScrollPage.nextPageOffset] for one, so an ordered
     * scroll pages through [OrderBy.startFrom] rather than through the id cursor.
     */
    @SerialName("order_by")
    public val orderBy: OrderBy? = null,
)

/**
 * Ordering for a `scroll`: a payload [key], a [direction], and the value to resume from.
 *
 * Qdrant requires a payload index on [key] — an ordered scroll over an unindexed field is rejected by
 * the server rather than falling back to a scan.
 */
@Serializable
public data class OrderBy(
    @SerialName("key")
    public val key: String,

    /** Ascending when `null`. */
    @SerialName("direction")
    public val direction: Direction? = null,

    /**
     * The order value to start from, **inclusive**: a number, or an RFC 3339 timestamp string. `null`
     * starts at the lowest value for [Direction.ASC] and the highest for [Direction.DESC].
     */
    @SerialName("start_from")
    public val startFrom: JsonPrimitive? = null,
)

/** One page of `scroll` results plus the cursor to the next page. */
@Serializable
public data class ScrollPage(
    @SerialName("points")
    public val points: List<Record>,

    /** Cursor for the next page, or `null` when the stream is exhausted. */
    @SerialName("next_page_offset")
    public val nextPageOffset: PointId? = null,
)
