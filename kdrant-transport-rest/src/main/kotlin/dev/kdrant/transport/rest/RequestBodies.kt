package dev.kdrant.transport.rest

import dev.kdrant.model.Filter
import dev.kdrant.model.PointId
import dev.kdrant.model.WithPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body for `POST /collections/{name}/points/count`. */
@Serializable
internal data class CountRequest(
    @SerialName("filter") val filter: Filter? = null,
    @SerialName("exact") val exact: Boolean? = null,
)

/** Body for `POST /collections/{name}/points` (retrieve by id). */
@Serializable
internal data class PointRequest(
    @SerialName("ids") val ids: List<PointId>,
    @SerialName("with_payload") val withPayload: WithPayload? = null,
    @SerialName("with_vector") val withVector: Boolean? = null,
)
