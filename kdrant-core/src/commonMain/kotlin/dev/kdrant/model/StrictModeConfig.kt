package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-collection limits Qdrant enforces on the requests it accepts.
 *
 * The point of strict mode is that the refusal happens on the server. A limit a client checks is a
 * limit the next client does not check, and a shared cluster has more than one client. These are the
 * knobs an operator sets so a query that would take the node down is refused rather than served.
 *
 * ```kotlin
 * qdrant.updateCollection("docs") {
 *     strictMode { enabled = true; maxQueryLimit = 1000; unindexedFilteringRetrieve = false }
 * }
 * ```
 *
 * Two of these describe the cluster's health rather than a policy, and are the reason this type is
 * here rather than only in a future release. [maxDiskUsagePercent] and [maxResidentMemoryPercent] make
 * a node refuse writes while still serving reads once it crosses them, which is the degraded state an
 * operator most needs the client to be predictable in: Kdrant raises
 * [dev.kdrant.KdrantException.ReadOnly] for it, which is retryable, rather than the credential failure
 * a bare 403 would otherwise look like.
 *
 * Qdrant takes further limits this does not model — the per-vector multivector and sparse sub-configs,
 * and the payload-index count. They are additions to this class rather than a different shape, which is
 * why it is a data class with every field defaulted to the server's own choice.
 */
@Serializable
public data class StrictModeConfig(
    /** Whether the limits below are enforced at all. */
    @SerialName("enabled")
    public val enabled: Boolean? = null,

    /** Largest `limit` an API without its own cap will accept. */
    @SerialName("max_query_limit")
    public val maxQueryLimit: Int? = null,

    /** Largest `timeout`, in seconds, a request may ask for. */
    @SerialName("max_timeout")
    public val maxTimeout: Int? = null,

    /** Allow filtering a search on a payload field with no index. `false` turns a full scan into an error. */
    @SerialName("unindexed_filtering_retrieve")
    public val unindexedFilteringRetrieve: Boolean? = null,

    /** Allow filtering an update on a payload field with no index. */
    @SerialName("unindexed_filtering_update")
    public val unindexedFilteringUpdate: Boolean? = null,

    /** Largest `hnswEf` a search may ask for. */
    @SerialName("search_max_hnsw_ef")
    public val searchMaxHnswEf: Int? = null,

    /** Whether an exact (brute-force) search is allowed. */
    @SerialName("search_allow_exact")
    public val searchAllowExact: Boolean? = null,

    /** Most points one upsert may carry. */
    @SerialName("upsert_max_batchsize")
    public val upsertMaxBatchSize: Int? = null,

    /** Most searches one batch may carry. */
    @SerialName("search_max_batchsize")
    public val searchMaxBatchSize: Int? = null,

    /** Reads per minute per replica; over it, Qdrant answers 429. */
    @SerialName("read_rate_limit")
    public val readRateLimit: Int? = null,

    /** Writes per minute per replica; over it, Qdrant answers 429. */
    @SerialName("write_rate_limit")
    public val writeRateLimit: Int? = null,

    /** Most points the collection may hold. */
    @SerialName("max_points_count")
    public val maxPointsCount: Long? = null,

    /** Conditions one filter may combine. */
    @SerialName("filter_max_conditions")
    public val filterMaxConditions: Int? = null,

    /**
     * Disk usage, as a percentage, past which the node refuses writes and keeps serving reads. This is
     * the read-only state [dev.kdrant.KdrantException.ReadOnly] names.
     */
    @SerialName("max_disk_usage_percent")
    public val maxDiskUsagePercent: Int? = null,

    /** Resident memory, as a percentage, past which memory-consuming writes are refused. */
    @SerialName("max_resident_memory_percent")
    public val maxResidentMemoryPercent: Int? = null,
) {
    init {
        // Qdrant validates both as 1..100 and rejects 0, which reads as "no disk allowed" rather than
        // as the disabled state a caller writing zero has in mind. Catching it here says so where the
        // caller can still fix it, instead of as a validation error from the server.
        maxDiskUsagePercent?.let {
            require(it in 1..100) { "maxDiskUsagePercent must be in 1..100, was $it. Use null to leave it unset." }
        }
        maxResidentMemoryPercent?.let {
            require(it in 1..100) {
                "maxResidentMemoryPercent must be in 1..100, was $it. Use null to leave it unset."
            }
        }
        readRateLimit?.let { require(it > 0) { "readRateLimit must be > 0, was $it" } }
        writeRateLimit?.let { require(it > 0) { "writeRateLimit must be > 0, was $it" } }
    }
}
