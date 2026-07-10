package dev.kdrant.transport.rest

import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire envelope for `POST /points/query`: `{"result": {"points": [...]}}`. */
@Serializable
internal data class QueryResponse(
    @SerialName("result") val result: QueryResult,
)

@Serializable
internal data class QueryResult(
    @SerialName("points") val points: List<ScoredPoint>,
)

/** Wire envelope for `POST /points/scroll`: `{"result": {"points": [...], "next_page_offset": ...}}`. */
@Serializable
internal data class ScrollResponse(
    @SerialName("result") val result: ScrollPage,
)
