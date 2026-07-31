package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A user-defined sharding key: either a name or a number, the two forms Qdrant accepts.
 *
 * Custom sharding lets a collection be partitioned on something the application knows about, a region
 * or a tenant, so a query that only concerns one of them touches one shard instead of all of them.
 */
@Serializable(with = ShardKeySerializer::class)
public sealed interface ShardKey {

    /** A named key, e.g. `region_1`. */
    public data class Name(public val value: String) : ShardKey

    /** A numeric key. */
    public data class Num(public val value: ULong) : ShardKey

    public companion object {
        public fun of(value: String): ShardKey = Name(value)
        public fun of(value: ULong): ShardKey = Num(value)
        public fun of(value: Long): ShardKey {
            require(value >= 0) { "a numeric shard key must be >= 0, was $value" }
            return Num(value.toULong())
        }
    }
}

internal object ShardKeySerializer : KSerializer<ShardKey> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.ShardKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ShardKey) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ShardKey requires JSON")
        json.encodeJsonElement(
            when (value) {
                is ShardKey.Name -> JsonPrimitive(value.value)
                is ShardKey.Num -> JsonPrimitive(value.value)
            },
        )
    }

    override fun deserialize(decoder: Decoder): ShardKey {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ShardKey requires JSON")
        val element = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("a shard key is a string or a number, got an object or array")
        return if (element.isString) ShardKey.Name(element.content) else ShardKey.Num(shardNumber(element))
    }

    private fun shardNumber(element: JsonPrimitive): ULong =
        element.longOrNull?.takeIf { it >= 0 }?.toULong()
            ?: element.content.toULongOrNull()
            ?: throw SerializationException(
                "a numeric shard key must be a non-negative integer, got ${element.content}",
            )
}

/**
 * State of one shard within a replica set. An unrecognized state from a newer server decodes to
 * [UNKNOWN] rather than failing the whole cluster-info response.
 */
@Serializable(with = ReplicaStateSerializer::class)
public enum class ReplicaState {
    ACTIVE,
    DEAD,
    PARTIAL,
    INITIALIZING,
    LISTENER,
    PARTIAL_SNAPSHOT,
    RECOVERY,
    RESHARDING,
    RESHARDING_SCALE_DOWN,
    ACTIVE_READ,
    MANUAL_RECOVERY,

    /** A state this client version does not recognize. */
    UNKNOWN,
}

internal object ReplicaStateSerializer : KSerializer<ReplicaState> {
    private val byWire = mapOf(
        "Active" to ReplicaState.ACTIVE,
        "Dead" to ReplicaState.DEAD,
        "Partial" to ReplicaState.PARTIAL,
        "Initializing" to ReplicaState.INITIALIZING,
        "Listener" to ReplicaState.LISTENER,
        "PartialSnapshot" to ReplicaState.PARTIAL_SNAPSHOT,
        "Recovery" to ReplicaState.RECOVERY,
        "Resharding" to ReplicaState.RESHARDING,
        "ReshardingScaleDown" to ReplicaState.RESHARDING_SCALE_DOWN,
        "ActiveRead" to ReplicaState.ACTIVE_READ,
        "ManualRecovery" to ReplicaState.MANUAL_RECOVERY,
    )
    private val toWire = byWire.entries.associate { (wire, state) -> state to wire }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.ReplicaState", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReplicaState) {
        encoder.encodeString(toWire[value] ?: "Unknown")
    }

    override fun deserialize(decoder: Decoder): ReplicaState =
        byWire[decoder.decodeString()] ?: ReplicaState.UNKNOWN
}

/** A shard served by the node that answered the request. */
@Serializable
public data class LocalShardInfo(
    @SerialName("shard_id") public val shardId: Int,
    @SerialName("shard_key") public val shardKey: ShardKey? = null,
    @SerialName("points_count") public val pointsCount: Long = 0,
    @SerialName("state") public val state: ReplicaState = ReplicaState.UNKNOWN,
)

/** A shard served by another peer. */
@Serializable
public data class RemoteShardInfo(
    @SerialName("shard_id") public val shardId: Int,
    @SerialName("shard_key") public val shardKey: ShardKey? = null,
    @SerialName("peer_id") public val peerId: Long,
    @SerialName("state") public val state: ReplicaState = ReplicaState.UNKNOWN,
)

/** A shard transfer in progress. [sync] distinguishes replicating a shard from moving it. */
@Serializable
public data class ShardTransferInfo(
    @SerialName("shard_id") public val shardId: Int,
    @SerialName("to_shard_id") public val toShardId: Int? = null,
    @SerialName("from") public val from: Long,
    @SerialName("to") public val to: Long,
    @SerialName("sync") public val sync: Boolean = false,
)

/** How a collection's shards are distributed right now, as the answering peer sees it. */
@Serializable
public data class CollectionClusterInfo(
    @SerialName("peer_id") public val peerId: Long,
    @SerialName("shard_count") public val shardCount: Int = 0,
    @SerialName("local_shards") public val localShards: List<LocalShardInfo> = emptyList(),
    @SerialName("remote_shards") public val remoteShards: List<RemoteShardInfo> = emptyList(),
    @SerialName("shard_transfers") public val shardTransfers: List<ShardTransferInfo> = emptyList(),
)

/**
 * A change to a collection's shard placement, applied through `POST /collections/{name}/cluster`.
 *
 * These move data between peers, so they are asynchronous: the request is accepted and the transfer
 * shows up in [CollectionClusterInfo.shardTransfers] until it finishes.
 *
 * Request-only, like [Condition]: Qdrant never returns one.
 */
@Serializable(with = ClusterOperationSerializer::class)
public sealed interface ClusterOperation {

    /** Move a shard to another peer, removing it from the source. */
    public data class MoveShard(
        public val shardId: Int,
        public val fromPeerId: Long,
        public val toPeerId: Long,
    ) : ClusterOperation

    /** Copy a shard to another peer, keeping the source: this is how a replica is added. */
    public data class ReplicateShard(
        public val shardId: Int,
        public val fromPeerId: Long,
        public val toPeerId: Long,
    ) : ClusterOperation

    /** Abort a transfer that is in progress. */
    public data class AbortTransfer(
        public val shardId: Int,
        public val fromPeerId: Long,
        public val toPeerId: Long,
    ) : ClusterOperation

    /** Drop a replica of [shardId] from [peerId]. */
    public data class DropReplica(
        public val shardId: Int,
        public val peerId: Long,
    ) : ClusterOperation
}

internal object ClusterOperationSerializer : KSerializer<ClusterOperation> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.ClusterOperation", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ClusterOperation) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ClusterOperation requires JSON")
        json.encodeJsonElement(
            buildJsonObject {
                when (value) {
                    is ClusterOperation.MoveShard -> putJsonObject("move_shard") {
                        put("shard_id", JsonPrimitive(value.shardId))
                        put("from_peer_id", JsonPrimitive(value.fromPeerId))
                        put("to_peer_id", JsonPrimitive(value.toPeerId))
                    }
                    is ClusterOperation.ReplicateShard -> putJsonObject("replicate_shard") {
                        put("shard_id", JsonPrimitive(value.shardId))
                        put("from_peer_id", JsonPrimitive(value.fromPeerId))
                        put("to_peer_id", JsonPrimitive(value.toPeerId))
                    }
                    is ClusterOperation.AbortTransfer -> putJsonObject("abort_transfer") {
                        put("shard_id", JsonPrimitive(value.shardId))
                        put("from_peer_id", JsonPrimitive(value.fromPeerId))
                        put("to_peer_id", JsonPrimitive(value.toPeerId))
                    }
                    is ClusterOperation.DropReplica -> putJsonObject("drop_replica") {
                        put("shard_id", JsonPrimitive(value.shardId))
                        put("peer_id", JsonPrimitive(value.peerId))
                    }
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): ClusterOperation =
        throw SerializationException("cluster operations are request-only; Qdrant never returns one")
}

/** Request body for `PUT /collections/{name}/shards`. */
@Serializable
public data class CreateShardKeyRequest(
    @SerialName("shard_key") public val shardKey: ShardKey,
    @SerialName("shards_number") public val shardsNumber: Int? = null,
    @SerialName("replication_factor") public val replicationFactor: Int? = null,
    @SerialName("placement") public val placement: List<Long>? = null,
)
