package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A search hit: a point with its similarity [score]. */
@Serializable
public data class ScoredPoint(
    @SerialName("id")
    public val id: PointId,

    @SerialName("score")
    public val score: Float,

    @SerialName("version")
    public val version: Long? = null,

    @SerialName("payload")
    public val payload: Payload? = null,

    @SerialName("vector")
    public val vector: VectorData? = null,
)
