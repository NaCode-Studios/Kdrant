package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Configuration for a single (dense) vector: dimensionality, metric, and storage options. */
@Serializable
public data class VectorParams(
    @SerialName("size")
    public val size: Long,

    @SerialName("distance")
    public val distance: Distance,

    /** Keep vectors memory-mapped on disk instead of in RAM. */
    @SerialName("on_disk")
    public val onDisk: Boolean? = null,

    @SerialName("datatype")
    public val datatype: VectorDatatype? = null,

    @SerialName("hnsw_config")
    public val hnswConfig: HnswConfig? = null,
)
