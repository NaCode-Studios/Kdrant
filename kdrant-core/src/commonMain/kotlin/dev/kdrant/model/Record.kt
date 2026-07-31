package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A point returned by `scroll` (no similarity score). */
@Serializable
public data class Record(
    @SerialName("id")
    public val id: PointId,

    @SerialName("payload")
    public val payload: Payload? = null,

    @SerialName("vector")
    public val vector: VectorData? = null,

    /**
     * The value of the ordering key, returned only for a scroll ordered by [OrderBy]. It is the cursor
     * an ordered scroll resumes from, since Qdrant returns no page offset for one.
     */
    @SerialName("order_value")
    public val orderValue: JsonPrimitive? = null,
)
