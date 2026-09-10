package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cluster-wide limits on node resources, introduced in Qdrant 1.19 to replace the per-collection
 * ceilings that strict mode carried.
 *
 * An unset limit is not a limit of zero: it means the resource is not capped. Nothing is enforced at
 * all while [enabled] is false, which is the default, so a config written with limits and left disabled
 * is a config that does nothing.
 */
@Serializable
public data class QuotaConfig(
    /** Whether the limits below are enforced. A quota that is off refuses nothing. */
    @SerialName("enabled")
    public val enabled: Boolean? = null,

    /**
     * Refuse memory-consuming updates once the process's resident memory reaches this percentage of
     * the memory available to it, which is the cgroup limit where one applies rather than the machine's.
     */
    @SerialName("max_resident_memory_percent")
    public val maxResidentMemoryPercent: Int? = null,

    /** Refuse disk-consuming updates once the storage filesystem is this full. */
    @SerialName("max_disk_usage_percent")
    public val maxDiskUsagePercent: Int? = null,

    /**
     * How far below a limit a resource has to fall before the node accepts work again.
     *
     * Without a margin a resource resting on its limit crosses it in both directions on the noise
     * between two readings, taking the node in and out of service each time and restarting any shard
     * recovery with it. Leave it unset to keep the server's default rather than pinning a number a
     * later Qdrant may want to revise; `0` releases as soon as usage is back under the limit.
     */
    @SerialName("release_margin_percent")
    public val releaseMarginPercent: Int? = null,
) {
    init {
        maxResidentMemoryPercent?.let {
            require(it in 1..100) { "maxResidentMemoryPercent must be in 1..100, was $it. Use null for no cap." }
        }
        maxDiskUsagePercent?.let {
            require(it in 1..100) { "maxDiskUsagePercent must be in 1..100, was $it. Use null for no cap." }
        }
        releaseMarginPercent?.let {
            require(it in 0..100) { "releaseMarginPercent must be in 0..100, was $it" }
        }
    }
}

/**
 * The quota in force and how close the cluster is to it.
 *
 * The configuration is cluster-wide and the utilization is not. [usage] is the node that answered, and
 * [peers] is what every peer that could be reached reports about itself, so one peer being comfortable
 * says nothing about the others. A peer missing from [peers] did not answer, which is worth seeing.
 */
@Serializable
public data class QuotaStatus(
    @SerialName("config")
    public val config: QuotaConfig,

    @SerialName("usage")
    public val usage: QuotaUsage,

    /** Keyed by peer id, and absent outside distributed mode, where there are no peers to ask. */
    @SerialName("peers")
    public val peers: Map<String, PeerQuotaUsage>? = null,
)

/** Utilization of the quota-managed resources on one node. A field is null where the platform hides it. */
@Serializable
public data class QuotaUsage(
    @SerialName("resident_memory_percent")
    public val residentMemoryPercent: Int? = null,

    @SerialName("disk_usage_percent")
    public val diskUsagePercent: Int? = null,
)

/** What one peer reports about the quota it is enforcing. */
@Serializable
public data class PeerQuotaUsage(
    /** Which limits this peer is currently refusing work over. */
    @SerialName("exceeded")
    public val exceeded: QuotaExceeded,

    @SerialName("resident_memory_percent")
    public val residentMemoryPercent: Int? = null,

    @SerialName("disk_usage_percent")
    public val diskUsagePercent: Int? = null,
)

/**
 * Which enforced limit a node is refusing work over, per resource, because they are freed by different
 * actions: disk by deleting or optimizing, memory by unloading.
 *
 * A flag outlasts the reading that set it. A resource that reaches its limit stays flagged until it has
 * fallen a margin below, so expect to see one set while the reported utilization is already back under
 * the limit. Null is not false: it means the node is not enforcing that resource at all, and reporting
 * it as within its limits would invite an alert that can never fire.
 */
@Serializable
public data class QuotaExceeded(
    @SerialName("resident_memory")
    public val residentMemory: Boolean? = null,

    @SerialName("disk_usage")
    public val diskUsage: Boolean? = null,
) {
    /** True when either enforced resource is currently refusing work. */
    public val any: Boolean get() = residentMemory == true || diskUsage == true
}
