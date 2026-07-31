package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single point to upsert: an [id], its [vector], and an optional [payload]. */
@Serializable
public data class PointStruct(
    @SerialName("id")
    public val id: PointId,

    @SerialName("vector")
    public val vector: VectorData,

    @SerialName("payload")
    public val payload: Payload? = null,
)
