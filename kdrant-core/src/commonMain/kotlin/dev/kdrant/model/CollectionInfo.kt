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

    /**
     * The collection's stored configuration. This is the read-back that makes a rerunnable bootstrap
     * script able to check what it found rather than assume it — see `ensureCollection`.
     */
    @SerialName("config")
    public val config: CollectionConfig? = null,

    /** The payload field indexes that exist, keyed by field name. */
    @SerialName("payload_schema")
    public val payloadSchema: Map<String, PayloadIndexInfo> = emptyMap(),
)

/** A collection's stored configuration, as returned by `getCollection`. */
@Serializable
public data class CollectionConfig(
    @SerialName("params")
    public val params: CollectionParams? = null,
)

/** The vector and sharding parameters a collection was created with. */
@Serializable
public data class CollectionParams(
    @SerialName("vectors")
    public val vectors: VectorsConfig? = null,

    @SerialName("sparse_vectors")
    public val sparseVectors: Map<String, SparseVectorParams>? = null,

    @SerialName("shard_number")
    public val shardNumber: Int? = null,

    @SerialName("replication_factor")
    public val replicationFactor: Int? = null,

    @SerialName("write_consistency_factor")
    public val writeConsistencyFactor: Int? = null,

    @SerialName("on_disk_payload")
    public val onDiskPayload: Boolean? = null,
)

/** What kind of index exists on a payload field, and how many points it covers. */
@Serializable
public data class PayloadIndexInfo(
    /**
     * The index type's wire name (`keyword`, `integer`, ...). Kept as a string so an index type added
     * by a newer Qdrant cannot fail the whole `getCollection` response; use [schemaType] for the
     * typed form.
     */
    @SerialName("data_type")
    public val dataType: String? = null,

    @SerialName("points")
    public val points: Long? = null,
) {
    /** [dataType] as a [PayloadSchemaType], or `null` if this client version does not know the type. */
    public val schemaType: PayloadSchemaType?
        get() = PayloadSchemaType.entries.firstOrNull { it.name.equals(dataType, ignoreCase = true) }
}
