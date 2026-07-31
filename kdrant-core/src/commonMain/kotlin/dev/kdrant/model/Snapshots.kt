package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Metadata for one snapshot, returned by the create and list operations. */
@Serializable
public data class SnapshotDescription(
    @SerialName("name")
    public val name: String,
    @SerialName("creation_time")
    public val creationTime: String? = null,
    @SerialName("size")
    public val size: Long,
    @SerialName("checksum")
    public val checksum: String? = null,
)

/**
 * Source-of-truth policy when recovering a snapshot into a collection that has replicas.
 * Ignored for a single-replica collection.
 */
@Serializable
public enum class SnapshotPriority {

    /** Restore the snapshot without any additional cross-replica synchronization. */
    @SerialName("no_sync")
    NO_SYNC,

    /** Prefer the snapshot's data over the collection's current state. */
    @SerialName("snapshot")
    SNAPSHOT,

    /** Prefer the collection's current state over the snapshot. */
    @SerialName("replica")
    REPLICA,
}
