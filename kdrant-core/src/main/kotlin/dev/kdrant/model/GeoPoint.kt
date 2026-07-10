package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A geographic point. Note Qdrant's field order: longitude then latitude. */
@Serializable
public data class GeoPoint(
    @SerialName("lon") public val lon: Double,
    @SerialName("lat") public val lat: Double,
)
