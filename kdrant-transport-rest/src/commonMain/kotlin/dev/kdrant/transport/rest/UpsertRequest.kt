package dev.kdrant.transport.rest

import dev.kdrant.model.PointStruct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** REST request body for `PUT /collections/{name}/points` (list form). */
@Serializable
internal data class UpsertRequest(
    @SerialName("points")
    val points: List<PointStruct>,
)
