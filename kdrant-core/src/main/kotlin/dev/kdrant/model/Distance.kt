package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Vector distance metric. Wire values match Qdrant exactly. */
@Serializable
public enum class Distance {
    @SerialName("Cosine")
    COSINE,

    @SerialName("Dot")
    DOT,

    @SerialName("Euclid")
    EUCLID,

    @SerialName("Manhattan")
    MANHATTAN,
}
