package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Fusion algorithm combining the results of several [Prefetch] sources. */
public enum class FusionAlgorithm { RRF, DBSF }

/** Sort direction for [QueryInterface.OrderBy]. */
public enum class Direction { ASC, DESC }

/**
 * The `query` of a `/points/query` request: what to do with the (optionally prefetched) candidates.
 *
 * Request-only — Qdrant never returns a query, so this type serializes but does not deserialize.
 */
@Serializable(with = QueryInterfaceSerializer::class)
public sealed interface QueryInterface {

    /** Nearest-neighbor search by an explicit dense vector. Serializes to a bare JSON array. */
    public data class Vector(public val values: List<Float>) : QueryInterface

    /** Nearest-neighbor search reusing the stored vector of an existing point ("more like this"). */
    public data class ById(public val id: PointId) : QueryInterface

    /** Nearest-neighbor search by a sparse query vector. Serializes to `{"indices":[...],"values":[...]}`. */
    public data class Sparse(
        public val indices: List<Int>,
        public val values: List<Float>,
    ) : QueryInterface

    /** Nearest-neighbor search by a multi-vector / late-interaction (ColBERT) query: `[[...],[...]]`. */
    public data class MultiVector(public val vectors: List<List<Float>>) : QueryInterface

    /**
     * Fuse the rankings of several [Prefetch] sources — the basis of hybrid search. Build it with
     * [rrf] (Reciprocal Rank Fusion, optionally parameterized) or [dbsf].
     */
    public data class Fusion(
        public val algorithm: FusionAlgorithm,
        public val rrfK: Int? = null,
        public val rrfWeights: List<Float>? = null,
    ) : QueryInterface {
        public companion object {
            /** Reciprocal Rank Fusion; [k] and per-prefetch [weights] are optional. */
            public fun rrf(k: Int? = null, weights: List<Float>? = null): Fusion =
                Fusion(FusionAlgorithm.RRF, k, weights)

            /** Distribution-Based Score Fusion. */
            public val dbsf: Fusion = Fusion(FusionAlgorithm.DBSF)
        }
    }

    /** Order points by a payload [key] (ascending by default). */
    public data class OrderBy(
        public val key: String,
        public val direction: Direction? = null,
    ) : QueryInterface

    /** Return a random sample of points. */
    public data object Sample : QueryInterface
}

/** Write-only serializer emitting each [QueryInterface] variant in Qdrant's `VectorInput | Query` shape. */
internal object QueryInterfaceSerializer : KSerializer<QueryInterface> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.QueryInterface")

    override fun serialize(encoder: Encoder, value: QueryInterface) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("QueryInterface can only be serialized to JSON")
        val element: JsonElement = when (value) {
            is QueryInterface.Vector ->
                JsonArray(value.values.map { JsonPrimitive(it) })

            is QueryInterface.ById ->
                json.json.encodeToJsonElement(PointId.serializer(), value.id)

            is QueryInterface.Sparse -> buildJsonObject {
                put("indices", JsonArray(value.indices.map { JsonPrimitive(it) }))
                put("values", JsonArray(value.values.map { JsonPrimitive(it) }))
            }

            is QueryInterface.MultiVector ->
                JsonArray(value.vectors.map { row -> JsonArray(row.map { JsonPrimitive(it) }) })

            is QueryInterface.Fusion -> buildJsonObject {
                if (value.algorithm == FusionAlgorithm.RRF && (value.rrfK != null || value.rrfWeights != null)) {
                    putJsonObject("rrf") {
                        value.rrfK?.let { put("k", it) }
                        value.rrfWeights?.let { put("weights", JsonArray(it.map(::JsonPrimitive))) }
                    }
                } else {
                    put("fusion", if (value.algorithm == FusionAlgorithm.RRF) "rrf" else "dbsf")
                }
            }

            is QueryInterface.OrderBy -> buildJsonObject {
                if (value.direction == null) {
                    put("order_by", value.key)
                } else {
                    putJsonObject("order_by") {
                        put("key", value.key)
                        put("direction", if (value.direction == Direction.ASC) "asc" else "desc")
                    }
                }
            }

            QueryInterface.Sample -> buildJsonObject { put("sample", "random") }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): QueryInterface =
        throw SerializationException("QueryInterface is request-only and is never deserialized")
}
