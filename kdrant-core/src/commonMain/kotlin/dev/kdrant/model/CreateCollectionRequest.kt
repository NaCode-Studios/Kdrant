package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `PUT /collections/{name}`.
 *
 * Models the collection settings used day-to-day. Advanced options (optimizers, quantization, WAL)
 * are not yet exposed; unknown fields are ignored, so the model can grow without breaking callers.
 */
@Serializable
public data class CreateCollectionRequest(
    /** Dense / named-dense vectors; may be `null` for a sparse-only collection. */
    @SerialName("vectors")
    public val vectors: VectorsConfig? = null,

    /** Named sparse vectors, keyed by name. */
    @SerialName("sparse_vectors")
    public val sparseVectors: Map<String, SparseVectorParams>? = null,

    @SerialName("hnsw_config")
    public val hnswConfig: HnswConfig? = null,

    /** Store payloads on disk instead of RAM (distinct from vector/HNSW on-disk settings). */
    @SerialName("on_disk_payload")
    public val onDiskPayload: Boolean? = null,

    @SerialName("shard_number")
    public val shardNumber: Int? = null,

    @SerialName("replication_factor")
    public val replicationFactor: Int? = null,

    @SerialName("optimizers_config")
    public val optimizersConfig: OptimizersConfig? = null,

    @SerialName("quantization_config")
    public val quantizationConfig: QuantizationConfig? = null,

    /** Server-enforced limits on the requests this collection accepts. */
    @SerialName("strict_mode_config")
    public val strictModeConfig: StrictModeConfig? = null,

    /**
     * Memory placement of payload storage. Overrides [onDiskPayload] when both are set.
     *
     * Appended rather than placed beside the flag it replaces, which would read better and would have
     * shifted every `componentN` after it: a destructuring or a positional `copy()` compiled against
     * `2.2.0` would then resolve to the wrong field. A field added to the end of a public data class
     * costs a recompile; one added to the middle costs more than that.
     */
    @SerialName("payload")
    public val payload: PayloadStorageParams? = null,
)
