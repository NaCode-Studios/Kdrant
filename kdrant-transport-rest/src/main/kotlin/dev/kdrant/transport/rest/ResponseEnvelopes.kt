package dev.kdrant.transport.rest

import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Qdrant wraps every REST response in `{"result": ..., "status": ..., "time": ...}`. */

@Serializable
internal data class QueryResponse(
    @SerialName("result") val result: QueryResult,
)

@Serializable
internal data class QueryResult(
    @SerialName("points") val points: List<ScoredPoint>,
)

@Serializable
internal data class ScrollResponse(
    @SerialName("result") val result: ScrollPage,
)

@Serializable
internal data class ExistsResponse(
    @SerialName("result") val result: ExistsResult,
)

@Serializable
internal data class ExistsResult(
    @SerialName("exists") val exists: Boolean,
)

@Serializable
internal data class CollectionInfoResponse(
    @SerialName("result") val result: CollectionInfo,
)

@Serializable
internal data class CountResponse(
    @SerialName("result") val result: CountResult,
)

@Serializable
internal data class CountResult(
    @SerialName("count") val count: Long,
)

@Serializable
internal data class RetrieveResponse(
    @SerialName("result") val result: List<Record>,
)
