package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Payload field index type for `createPayloadIndex`. Indexing a field makes filtering on it scale. */
@Serializable
public enum class PayloadSchemaType {
    @SerialName("keyword")
    KEYWORD,

    @SerialName("integer")
    INTEGER,

    @SerialName("float")
    FLOAT,

    @SerialName("geo")
    GEO,

    @SerialName("text")
    TEXT,

    @SerialName("bool")
    BOOL,

    @SerialName("datetime")
    DATETIME,

    @SerialName("uuid")
    UUID,
}
