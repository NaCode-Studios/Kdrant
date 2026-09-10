package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a Qdrant component is placed in memory. Data is always persisted on disk; this controls
 * preloading and eviction only. When set with a legacy `on_disk` flag, this value wins.
 */
@Serializable
public enum class Memory {
    /** Load lazily and cache with use. Best for large or rarely queried components. */
    @SerialName("cold")
    COLD,

    /** Preload into the OS page cache, but allow eviction under memory pressure. */
    @SerialName("cached")
    CACHED,

    /** Keep resident and never evict. Not supported for dense-vector or payload storage. */
    @SerialName("pinned")
    PINNED,
}

/** Configuration for the collection's payload storage. */
@Serializable
public data class PayloadStorageParams(
    /** Memory placement of payload values; overrides [CreateCollectionRequest.onDiskPayload]. */
    @SerialName("memory")
    public val memory: Memory? = null,
)
