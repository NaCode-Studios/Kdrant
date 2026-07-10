package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Optimization/health status of a collection. Unrecognized values (from a newer server) decode
 * to [UNKNOWN] rather than failing the whole response.
 */
@Serializable(with = CollectionStatusSerializer::class)
public enum class CollectionStatus {
    /** Ready to serve. */
    GREEN,

    /** Optimization in progress. */
    YELLOW,

    /** Optimization pending. */
    GREY,

    /** An unrecoverable error occurred. */
    RED,

    /** A status value this client version does not recognize. */
    UNKNOWN,
}

internal object CollectionStatusSerializer : KSerializer<CollectionStatus> {
    private val byWire = mapOf(
        "green" to CollectionStatus.GREEN,
        "yellow" to CollectionStatus.YELLOW,
        "grey" to CollectionStatus.GREY,
        "red" to CollectionStatus.RED,
    )
    private val toWire = byWire.entries.associate { (wire, status) -> status to wire }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.CollectionStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CollectionStatus) {
        encoder.encodeString(toWire[value] ?: "unknown")
    }

    override fun deserialize(decoder: Decoder): CollectionStatus =
        byWire[decoder.decodeString()] ?: CollectionStatus.UNKNOWN
}

/** Summary of a collection returned by `getCollection`. */
@Serializable
public data class CollectionInfo(
    @SerialName("status")
    public val status: CollectionStatus = CollectionStatus.UNKNOWN,

    @SerialName("points_count")
    public val pointsCount: Long? = null,

    @SerialName("indexed_vectors_count")
    public val indexedVectorsCount: Long? = null,

    @SerialName("segments_count")
    public val segmentsCount: Int? = null,
)
