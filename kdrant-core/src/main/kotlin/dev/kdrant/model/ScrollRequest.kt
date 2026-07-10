package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
