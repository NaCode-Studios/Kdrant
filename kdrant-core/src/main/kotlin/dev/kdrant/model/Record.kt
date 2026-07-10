package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A point returned by `scroll` (no similarity score). */
@Serializable
public data class Record(
    @SerialName("id")
    public val id: PointId,

    @SerialName("payload")
    public val payload: Payload? = null,

    @SerialName("vector")
    public val vector: VectorData? = null,
)
