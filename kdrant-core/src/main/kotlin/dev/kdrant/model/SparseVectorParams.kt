package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Config for a named sparse vector under [CreateCollectionRequest.sparseVectors]. */
@Serializable
public data class SparseVectorParams(
    /** Query-time value modification (e.g. [Modifier.IDF] for BM25-style scoring). */
    @SerialName("modifier")
    public val modifier: Modifier? = null,
)

/** Query-time modification applied to sparse-vector values. */
@Serializable
public enum class Modifier {
    @SerialName("none")
    NONE,

    /** Inverse document frequency, from collection statistics (BM25-style scoring). */
    @SerialName("idf")
    IDF,
}

/** Config enabling multi-vector (late-interaction / ColBERT) storage on a dense vector. */
@Serializable
public data class MultiVectorConfig(
    @SerialName("comparator")
    public val comparator: MultiVectorComparator,
)

/** How multi-vectors are compared. */
@Serializable
public enum class MultiVectorComparator {
    @SerialName("max_sim")
    MAX_SIM,
}
