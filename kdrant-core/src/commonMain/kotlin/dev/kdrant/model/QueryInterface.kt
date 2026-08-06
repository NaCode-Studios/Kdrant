package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Fusion algorithm combining the results of several [Prefetch] sources. */
public enum class FusionAlgorithm { RRF, DBSF }

/**
 * Sort direction for [QueryInterface.OrderBy] and for an ordered [dev.kdrant.model.ScrollRequest].
 * Qdrant spells these lowercase on the wire.
 */
@Serializable
public enum class Direction {
    @SerialName("asc")
    ASC,

    @SerialName("desc")
    DESC,
}

/** How [QueryInterface.Recommend] uses positive/negative examples. Default (`null`) is average-vector. */
public enum class RecommendStrategy { AVERAGE_VECTOR, BEST_SCORE, SUM_SCORES }

/**
 * The `query` of a `/points/query` request: what to do with the (optionally prefetched) candidates.
 *
 * Request-only — Qdrant never returns a query, so this type serializes but does not deserialize.
 */
@Serializable(with = QueryInterfaceSerializer::class)
public sealed interface QueryInterface {

    /** Nearest-neighbor search by an explicit dense vector. Serializes to a bare JSON array. */
    public data class Vector(public val values: List<Float>) : VectorInput

    /**
     * Nearest-neighbor search by a dense vector backed by a [FloatArray] — a zero-boxing fast path that
     * serializes straight to a bare JSON array. Equality is by content.
     */
    public class VectorArray(public val values: FloatArray) : VectorInput {
        override fun equals(other: Any?): Boolean =
            this === other || (other is VectorArray && values.contentEquals(other.values))

        override fun hashCode(): Int = values.contentHashCode()

        override fun toString(): String = "VectorArray(values=${values.contentToString()})"
    }

    /** Nearest-neighbor search reusing the stored vector of an existing point ("more like this"). */
    public data class ById(public val id: PointId) : VectorInput

    /** Nearest-neighbor search by a sparse query vector. Serializes to `{"indices":[...],"values":[...]}`. */
    public data class Sparse(
        public val indices: List<Int>,
        public val values: List<Float>,
    ) : VectorInput

    /** Nearest-neighbor search by a multi-vector / late-interaction (ColBERT) query: `[[...],[...]]`. */
    public data class MultiVector(public val vectors: List<List<Float>>) : VectorInput

    /**
     * Nearest-neighbor search by text, an image or a custom object the **server** embeds, rather than
     * by a vector the caller computed. Needs a Qdrant with an inference provider. See [InferenceInput].
     */
    public data class Inference(public val input: InferenceInput) : VectorInput

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

    /**
     * A nearest search written in its long form, which is what carries [mmr]. The short form, a bare
     * [VectorInput], means the same thing without reranking.
     */
    public data class Nearest(
        public val input: VectorInput,
        public val mmr: Mmr? = null,
    ) : QueryInterface

    /**
     * Rescore the candidates with an arithmetic [formula] instead of by vector distance.
     *
     * @property defaults values substituted for payload keys a candidate does not have. Without an
     *   entry, a missing key fails the query rather than being treated as zero.
     */
    public data class Formula(
        public val formula: Expression,
        public val defaults: Map<String, JsonPrimitive> = emptyMap(),
    ) : QueryInterface

    /** Recommend points close to the [positive] examples and far from the [negative] ones. */
    public data class Recommend(
        public val positive: List<VectorInput> = emptyList(),
        public val negative: List<VectorInput> = emptyList(),
        public val strategy: RecommendStrategy? = null,
    ) : QueryInterface

    /** Guided search: rank by proximity to [target], constrained by [context] example pairs. */
    public data class Discover(
        public val target: VectorInput,
        public val context: List<ContextPair> = emptyList(),
    ) : QueryInterface

    /** Context search: no target, only [pairs] steering the result region. */
    public data class Context(
        public val pairs: List<ContextPair> = emptyList(),
    ) : QueryInterface
}

/**
 * A bare vector input — usable directly as a nearest-search [QueryInterface] and as a positive/negative
 * example inside [QueryInterface.Recommend], [QueryInterface.Discover] and [ContextPair]. One of a dense
 * [QueryInterface.Vector], a stored-point [QueryInterface.ById], a [QueryInterface.Sparse], a
 * [QueryInterface.MultiVector], or a [QueryInterface.Inference] the server embeds.
 */
public sealed interface VectorInput : QueryInterface

/** A positive/negative example pair for discovery and context search. */
public data class ContextPair(
    public val positive: VectorInput,
    public val negative: VectorInput,
)

/** Write-only serializer emitting each [QueryInterface] variant in Qdrant's `VectorInput | Query` shape. */
internal object QueryInterfaceSerializer : KSerializer<QueryInterface> {
    private val floatArray = FloatArraySerializer()

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.QueryInterface")

    override fun serialize(encoder: Encoder, value: QueryInterface) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("QueryInterface can only be serialized to JSON")
        if (value is QueryInterface.VectorArray) {
            // Zero-boxing fast path: write the FloatArray straight to a JSON number array.
            encoder.encodeSerializableValue(floatArray, value.values)
        } else {
            json.encodeJsonElement(toElement(json.json, value))
        }
    }

    private fun toElement(json: Json, value: QueryInterface): JsonElement = when (value) {
        is QueryInterface.Vector ->
            JsonArray(value.values.map { JsonPrimitive(it) })

        is QueryInterface.VectorArray ->
            JsonArray(value.values.map { JsonPrimitive(it) })

        is QueryInterface.ById ->
            json.encodeToJsonElement(PointId.serializer(), value.id)

        is QueryInterface.Sparse -> buildJsonObject {
            put("indices", JsonArray(value.indices.map { JsonPrimitive(it) }))
            put("values", JsonArray(value.values.map { JsonPrimitive(it) }))
        }

        is QueryInterface.MultiVector ->
            JsonArray(value.vectors.map { row -> JsonArray(row.map { JsonPrimitive(it) }) })

        is QueryInterface.Inference ->
            json.encodeToJsonElement(InferenceInputSerializer, value.input)

        is QueryInterface.Fusion -> fusionElement(value)

        is QueryInterface.OrderBy -> orderByElement(value)

        QueryInterface.Sample -> buildJsonObject { put("sample", "random") }

        is QueryInterface.Nearest -> nearestElement(json, value)

        is QueryInterface.Formula -> formulaElement(json, value)

        is QueryInterface.Recommend -> recommendElement(json, value)

        else -> compositeElement(json, value)
    }

    private fun fusionElement(value: QueryInterface.Fusion): JsonElement = buildJsonObject {
        if (value.algorithm == FusionAlgorithm.RRF && (value.rrfK != null || value.rrfWeights != null)) {
            putJsonObject("rrf") {
                value.rrfK?.let { put("k", it) }
                value.rrfWeights?.let { put("weights", JsonArray(it.map { w -> JsonPrimitive(w) })) }
            }
        } else {
            put("fusion", if (value.algorithm == FusionAlgorithm.RRF) "rrf" else "dbsf")
        }
    }

    private fun orderByElement(value: QueryInterface.OrderBy): JsonElement = buildJsonObject {
        if (value.direction == null) {
            put("order_by", value.key)
        } else {
            putJsonObject("order_by") {
                put("key", value.key)
                put("direction", if (value.direction == Direction.ASC) "asc" else "desc")
            }
        }
    }

    private fun nearestElement(json: Json, value: QueryInterface.Nearest): JsonElement = buildJsonObject {
        put("nearest", toElement(json, value.input))
        value.mmr?.let { put("mmr", json.encodeToJsonElement(Mmr.serializer(), it)) }
    }

    private fun formulaElement(json: Json, value: QueryInterface.Formula): JsonElement = buildJsonObject {
        put("formula", json.encodeToJsonElement(Expression.serializer(), value.formula))
        if (value.defaults.isNotEmpty()) {
            put("defaults", JsonObject(value.defaults))
        }
    }

    private fun recommendElement(json: Json, value: QueryInterface.Recommend): JsonElement = buildJsonObject {
        putJsonObject("recommend") {
            if (value.positive.isNotEmpty()) {
                put("positive", JsonArray(value.positive.map { toElement(json, it) }))
            }
            if (value.negative.isNotEmpty()) {
                put("negative", JsonArray(value.negative.map { toElement(json, it) }))
            }
            value.strategy?.let { put("strategy", strategyWire(it)) }
        }
    }

    /** Discover and context, the two remaining shapes that carry example pairs. */
    private fun compositeElement(json: Json, value: QueryInterface): JsonElement = when (value) {
        is QueryInterface.Discover -> buildJsonObject {
            putJsonObject("discover") {
                put("target", toElement(json, value.target))
                put("context", JsonArray(value.context.map { pairElement(json, it) }))
            }
        }

        is QueryInterface.Context -> buildJsonObject {
            put("context", JsonArray(value.pairs.map { pairElement(json, it) }))
        }

        else -> throw SerializationException("unhandled query shape ${value::class.simpleName}")
    }

    private fun pairElement(json: Json, pair: ContextPair): JsonElement = buildJsonObject {
        put("positive", toElement(json, pair.positive))
        put("negative", toElement(json, pair.negative))
    }

    private fun strategyWire(strategy: RecommendStrategy): String = when (strategy) {
        RecommendStrategy.AVERAGE_VECTOR -> "average_vector"
        RecommendStrategy.BEST_SCORE -> "best_score"
        RecommendStrategy.SUM_SCORES -> "sum_scores"
    }

    override fun deserialize(decoder: Decoder): QueryInterface =
        throw SerializationException("QueryInterface is request-only and is never deserialized")
}
