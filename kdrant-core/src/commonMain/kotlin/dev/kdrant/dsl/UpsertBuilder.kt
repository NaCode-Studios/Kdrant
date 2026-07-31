package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Payload
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.VectorData

/** DSL for `upsert`: accumulate points with [point]. */
@KdrantDsl
public class UpsertBuilder {
    private val points: MutableList<PointStruct> = mutableListOf()

    public fun point(id: PointId, configure: PointBuilder.() -> Unit) {
        points += PointBuilder(id).apply(configure).build()
    }

    public fun point(id: Long, configure: PointBuilder.() -> Unit): Unit =
        point(PointId.num(id), configure)

    public fun point(id: ULong, configure: PointBuilder.() -> Unit): Unit =
        point(PointId.num(id), configure)

    public fun point(id: String, configure: PointBuilder.() -> Unit): Unit =
        point(PointId.uuid(id), configure)

    internal fun build(): List<PointStruct> {
        require(points.isNotEmpty()) { "upsert requires at least one point()" }
        return points.toList()
    }
}

/** DSL for a single point's vector and payload. */
@KdrantDsl
public class PointBuilder internal constructor(private val id: PointId) {
    private var vector: VectorData? = null
    private var payload: Payload? = null

    /** Single anonymous dense vector. */
    public fun vector(values: List<Float>) {
        vector = VectorData.Dense(values)
    }

    /** Single anonymous dense vector — zero-boxing: the values are kept as a [FloatArray], not boxed. */
    public fun vector(vararg values: Float) {
        vector = VectorData.DenseArray(values)
    }

    /** Named dense vectors. */
    public fun vector(vectors: Map<String, List<Float>>) {
        vector = VectorData.Named(vectors.mapValues { VectorData.Dense(it.value) })
    }

    /** Named dense vectors, e.g. `vector("text" to listOf(...), "image" to listOf(...))`. */
    public fun vector(vararg vectors: Pair<String, List<Float>>) {
        vector = VectorData.Named(vectors.associate { (name, values) -> name to VectorData.Dense(values) })
    }

    /** Set the vector(s) directly — for sparse, multi-vector, or mixed named vectors. */
    public fun vector(data: VectorData) {
        vector = data
    }

    /** Payload from key/value pairs, e.g. `payload("lang" to "it", "year" to 2024)`. */
    public fun payload(vararg pairs: Pair<String, Any?>) {
        payload = payloadOf(*pairs)
    }

    /** Payload from a pre-built [Payload] (JSON object). */
    public fun payload(payload: Payload) {
        this.payload = payload
    }

    /** Payload via the [PayloadBuilder] DSL. */
    public fun payload(configure: PayloadBuilder.() -> Unit) {
        payload = PayloadBuilder().apply(configure).build()
    }

    internal fun build(): PointStruct {
        val vector = requireNotNull(vector) {
            "point $id has no vector; call vector(...)"
        }
        return PointStruct(id, vector, payload)
    }
}
