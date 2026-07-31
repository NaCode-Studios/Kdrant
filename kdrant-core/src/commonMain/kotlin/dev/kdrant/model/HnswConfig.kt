package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HNSW index tuning. All fields are optional; omitted ones fall back to Qdrant's defaults.
 * Can be set collection-wide or overridden per named vector.
 */
@Serializable
public data class HnswConfig(
    @SerialName("m")
    public val m: Int? = null,

    @SerialName("ef_construct")
    public val efConstruct: Int? = null,

    @SerialName("full_scan_threshold")
    public val fullScanThreshold: Int? = null,

    @SerialName("max_indexing_threads")
    public val maxIndexingThreads: Int? = null,

    /** Store the HNSW graph on disk (distinct from vector/payload on-disk settings). */
    @SerialName("on_disk")
    public val onDisk: Boolean? = null,

    /** Dedicated `m` for payload-based indexes (multitenancy). */
    @SerialName("payload_m")
    public val payloadM: Int? = null,
)
