package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for `PATCH /collections/{name}` — tune an existing collection's config. */
@Serializable
public data class UpdateCollectionRequest(
    @SerialName("optimizers_config")
    public val optimizersConfig: OptimizersConfig? = null,

    @SerialName("hnsw_config")
    public val hnswConfig: HnswConfig? = null,

    @SerialName("quantization_config")
    public val quantizationConfig: QuantizationConfig? = null,

    /** Server-enforced limits on the requests this collection accepts. */
    @SerialName("strict_mode_config")
    public val strictModeConfig: StrictModeConfig? = null,

    /** Replication, read fan-out and payload placement. See [CollectionParamsDiff]. */
    @SerialName("params")
    public val params: CollectionParamsDiff? = null,
)

/**
 * The parts of a collection's configuration that can be changed after it exists.
 *
 * Several of these could only be chosen at creation before, which mattered most for the newest one:
 * memory tiers arrived in Qdrant 1.19 and `payloadMemory` set a collection's payload placement at
 * creation, so moving an existing collection from `cold` to `cached`, which is the whole point of a tier,
 * meant recreating the collection. The replication factor is the same story for a cluster that grew.
 *
 * A field left null is left unchanged, which is the opposite of how [QuotaConfig] behaves and is worth
 * knowing before using either.
 *
 * `vectors` and `sparseVectors` are on Qdrant's own update request and deliberately not here: they change
 * what the collection stores rather than how it is placed, and they belong with the vector-config work
 * rather than with this.
 */
@Suppress("DEPRECATION") // Carries the deprecated flag for a caller on an older server.
@Serializable
public data class CollectionParamsDiff(
    /** Replicas the cluster tries to maintain for each shard. */
    @SerialName("replication_factor")
    public val replicationFactor: Int? = null,

    /** Replicas that have to acknowledge a write for it to count. */
    @SerialName("write_consistency_factor")
    public val writeConsistencyFactor: Int? = null,

    /**
     * Extra remote nodes every read is also sent to, answering with the first response back.
     *
     * It buys tail latency with load: the same read runs on more peers. `0`, the server's default, sends
     * it to one.
     */
    @SerialName("read_fan_out_factor")
    public val readFanOutFactor: Int? = null,

    /** How long to wait before trying another replica, in milliseconds. */
    @SerialName("read_fan_out_delay_ms")
    public val readFanOutDelayMs: Long? = null,

    /** Payload placement. Overrides [onDiskPayload] when both are set. */
    @SerialName("payload")
    public val payload: PayloadStorageParams? = null,

    /** Deprecated by Qdrant 1.19 in favour of [payload]. */
    @Deprecated(
        "Qdrant 1.19 replaced this with payload.memory, which distinguishes three cases where a boolean " +
            "has two.",
        ReplaceWith("payload = PayloadStorageParams(Memory.COLD)", "dev.kdrant.model.PayloadStorageParams"),
    )
    @SerialName("on_disk_payload")
    public val onDiskPayload: Boolean? = null,
) {
    init {
        replicationFactor?.let { require(it >= 1) { "replicationFactor must be >= 1, was $it" } }
        writeConsistencyFactor?.let { require(it >= 1) { "writeConsistencyFactor must be >= 1, was $it" } }
        readFanOutFactor?.let { require(it >= 0) { "readFanOutFactor must be >= 0, was $it" } }
        readFanOutDelayMs?.let { require(it >= 0) { "readFanOutDelayMs must be >= 0, was $it" } }
    }
}
