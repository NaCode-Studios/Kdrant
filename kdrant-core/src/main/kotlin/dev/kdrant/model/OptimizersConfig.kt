package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Optimizer tuning for a collection. All fields are optional; on create, omitted ones use Qdrant's
 * defaults; on update, omitted ones keep the current value.
 */
@Serializable
public data class OptimizersConfig(
    /** Target number of segments the optimizer keeps. */
    @SerialName("default_segment_number")
    public val defaultSegmentNumber: Int? = null,

    /** Number of vectors in a segment before a vector index is built (0 disables ANN). */
    @SerialName("indexing_threshold")
    public val indexingThreshold: Int? = null,

    /** Number of vectors in a segment before it is memory-mapped to disk. */
    @SerialName("memmap_threshold")
    public val memmapThreshold: Int? = null,

    @SerialName("flush_interval_sec")
    public val flushIntervalSec: Int? = null,

    @SerialName("max_optimization_threads")
    public val maxOptimizationThreads: Int? = null,

    @SerialName("deleted_threshold")
    public val deletedThreshold: Double? = null,

    @SerialName("vacuum_min_vector_number")
    public val vacuumMinVectorNumber: Int? = null,

    @SerialName("max_segment_size")
    public val maxSegmentSize: Int? = null,
)
