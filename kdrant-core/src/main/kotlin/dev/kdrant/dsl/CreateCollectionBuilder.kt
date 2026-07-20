package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.Distance
import dev.kdrant.model.HnswConfig
import dev.kdrant.model.Modifier
import dev.kdrant.model.MultiVectorComparator
import dev.kdrant.model.MultiVectorConfig
import dev.kdrant.model.OptimizersConfig
import dev.kdrant.model.QuantizationConfig
import dev.kdrant.model.SparseVectorParams
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.model.VectorDatatype
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig

/** DSL for `createCollection`. Configure exactly one of a single vector or one-or-more named vectors. */
@KdrantDsl
public class CreateCollectionBuilder {

    private var singleVector: VectorParams? = null
    private val namedVectors: MutableMap<String, VectorParams> = linkedMapOf()
    private val sparseVectors: MutableMap<String, SparseVectorParams> = linkedMapOf()

    /** Store payloads on disk instead of RAM. */
    public var onDiskPayload: Boolean? = null

    /** Collection-wide HNSW index tuning. */
    public var hnswConfig: HnswConfig? = null

    /** Number of shards to split the collection into. */
    public var shardNumber: Int? = null

    /** How many replicas of each shard to keep. */
    public var replicationFactor: Int? = null

    /** Optimizer tuning. */
    public var optimizers: OptimizersConfig? = null

    /** Vector quantization, to shrink the collection's memory footprint. */
    public var quantization: QuantizationConfig? = null

    /** Configure a single anonymous vector. Mutually exclusive with [namedVector]. */
    public fun vector(configure: VectorParamsBuilder.() -> Unit) {
        singleVector = VectorParamsBuilder().apply(configure).build()
    }

    /** Add a named vector. Repeatable. Mutually exclusive with [vector]. */
    public fun namedVector(name: String, configure: VectorParamsBuilder.() -> Unit) {
        namedVectors[name] = VectorParamsBuilder().apply(configure).build()
    }

    /** Add a named sparse vector. Repeatable; may coexist with dense/named vectors (hybrid search). */
    public fun sparseVector(name: String, configure: SparseVectorParamsBuilder.() -> Unit = {}) {
        sparseVectors[name] = SparseVectorParamsBuilder().apply(configure).build()
    }

    internal fun build(): CreateCollectionRequest {
        val single = singleVector
        require(single == null || namedVectors.isEmpty()) {
            "Use either vector { } (a single anonymous vector) or namedVector(...), not both"
        }
        val vectors: VectorsConfig? = when {
            single != null -> VectorsConfig.Single(single)
            namedVectors.isNotEmpty() -> VectorsConfig.Named(namedVectors.toMap())
            else -> null
        }
        require(vectors != null || sparseVectors.isNotEmpty()) {
            "A collection needs at least one vector: call vector { }, namedVector(...), or sparseVector(...)"
        }
        shardNumber?.let { require(it > 0) { "shardNumber must be > 0, was $it" } }
        replicationFactor?.let { require(it > 0) { "replicationFactor must be > 0, was $it" } }
        return CreateCollectionRequest(
            vectors = vectors,
            sparseVectors = sparseVectors.takeIf { it.isNotEmpty() },
            hnswConfig = hnswConfig,
            onDiskPayload = onDiskPayload,
            shardNumber = shardNumber,
            replicationFactor = replicationFactor,
            optimizersConfig = optimizers,
            quantizationConfig = quantization,
        )
    }
}

/** DSL for `updateCollection`. All fields optional; omitted ones keep the collection's current value. */
@KdrantDsl
public class UpdateCollectionBuilder {
    /** Optimizer tuning. */
    public var optimizers: OptimizersConfig? = null

    /** HNSW index tuning. */
    public var hnsw: HnswConfig? = null

    /** Vector quantization. */
    public var quantization: QuantizationConfig? = null

    internal fun build(): UpdateCollectionRequest = UpdateCollectionRequest(
        optimizersConfig = optimizers,
        hnswConfig = hnsw,
        quantizationConfig = quantization,
    )
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

    /** Enable multi-vector (late-interaction / ColBERT) storage with this comparator. */
    public var multivector: MultiVectorComparator? = null

    internal fun build(): VectorParams {
        val size = requireNotNull(size) { "vector 'size' is required" }
        val distance = requireNotNull(distance) { "vector 'distance' is required" }
        require(size > 0) { "vector 'size' must be > 0, was $size" }
        return VectorParams(size, distance, onDisk, datatype, hnswConfig, multivector?.let { MultiVectorConfig(it) })
    }
}

/** DSL for a sparse vector's parameters. */
@KdrantDsl
public class SparseVectorParamsBuilder {
    /** Query-time value modification (e.g. [Modifier.IDF] for BM25-style scoring). */
    public var modifier: Modifier? = null

    internal fun build(): SparseVectorParams = SparseVectorParams(modifier)
}
