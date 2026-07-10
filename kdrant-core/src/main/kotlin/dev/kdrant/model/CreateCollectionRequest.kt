package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `PUT /collections/{name}`.
 *
 * Models the collection settings used day-to-day. Advanced options (optimizers, quantization,
 * sparse vectors, WAL) are not yet exposed; unknown fields are ignored, so the model can grow
 * without breaking existing callers.
 */
@Serializable
public data class CreateCollectionRequest(
    @SerialName("vectors")
    public val vectors: VectorsConfig,

    @SerialName("hnsw_config")
    public val hnswConfig: HnswConfig? = null,

    /** Store payloads on disk instead of RAM (distinct from vector/HNSW on-disk settings). */
    @SerialName("on_disk_payload")
    public val onDiskPayload: Boolean? = null,

    @SerialName("shard_number")
    public val shardNumber: Int? = null,

    @SerialName("replication_factor")
    public val replicationFactor: Int? = null,
)
