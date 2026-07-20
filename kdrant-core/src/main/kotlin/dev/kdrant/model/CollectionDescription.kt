package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A collection's name, as returned by `listCollections`. */
@Serializable
public data class CollectionDescription(
    @SerialName("name")
    public val name: String,
)
