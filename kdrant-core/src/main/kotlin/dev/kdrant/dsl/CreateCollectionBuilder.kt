package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.Distance
import dev.kdrant.model.HnswConfig
import dev.kdrant.model.VectorDatatype
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig

/** DSL for `createCollection`. Configure exactly one of a single vector or one-or-more named vectors. */
@KdrantDsl
public class CreateCollectionBuilder {

    private var singleVector: VectorParams? = null
    private val namedVectors: MutableMap<String, VectorParams> = linkedMapOf()

    /** Store payloads on disk instead of RAM. */
    public var onDiskPayload: Boolean? = null

    /** Collection-wide HNSW index tuning. */
    public var hnswConfig: HnswConfig? = null

    /** Number of shards to split the collection into. */
    public var shardNumber: Int? = null

    /** How many replicas of each shard to keep. */
    public var replicationFactor: Int? = null

    /** Configure a single anonymous vector. Mutually exclusive with [namedVector]. */
    public fun vector(configure: VectorParamsBuilder.() -> Unit) {
        singleVector = VectorParamsBuilder().apply(configure).build()
    }

    /** Add a named vector. Repeatable. Mutually exclusive with [vector]. */
    public fun namedVector(name: String, configure: VectorParamsBuilder.() -> Unit) {
        namedVectors[name] = VectorParamsBuilder().apply(configure).build()
    }

    internal fun build(): CreateCollectionRequest {
        val single = singleVector
        val vectors: VectorsConfig = when {
            single != null && namedVectors.isNotEmpty() ->
                throw IllegalArgumentException(
                    "Use either vector { } (a single anonymous vector) or namedVector(...), not both",
                )
            single != null -> VectorsConfig.Single(single)
            namedVectors.isNotEmpty() -> VectorsConfig.Named(namedVectors.toMap())
            else -> throw IllegalArgumentException(
                "A collection needs at least one vector: call vector { } or namedVector(...)",
            )
        }
        return CreateCollectionRequest(
            vectors = vectors,
            hnswConfig = hnswConfig,
            onDiskPayload = onDiskPayload,
            shardNumber = shardNumber,
            replicationFactor = replicationFactor,
        )
    }
}

/** DSL for a single vector's parameters. */
@KdrantDsl
public class VectorParamsBuilder {
    /** Vector dimensionality. Required. */
    public var size: Long? = null

    /** Distance metric used for search. Required. */
    public var distance: Distance? = null

    /** Store this vector on disk instead of RAM. */
    public var onDisk: Boolean? = null

    /** Element storage datatype (defaults to float32). */
    public var datatype: VectorDatatype? = null

    /** Per-vector HNSW index tuning. */
    public var hnswConfig: HnswConfig? = null

    internal fun build(): VectorParams {
        val size = requireNotNull(size) { "vector 'size' is required" }
        val distance = requireNotNull(distance) { "vector 'distance' is required" }
        return VectorParams(size, distance, onDisk, datatype, hnswConfig)
    }
}
