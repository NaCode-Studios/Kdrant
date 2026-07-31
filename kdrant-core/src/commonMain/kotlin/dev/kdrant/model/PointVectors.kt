package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A point's id and the vector(s) to write, for `updateVectors`. */
@Serializable
public data class PointVectors(
    @SerialName("id")
    public val id: PointId,

    @SerialName("vector")
    public val vector: VectorData,
)
