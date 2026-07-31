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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An arithmetic expression over a point's score and payload, evaluated by Qdrant to rerank the
 * candidates a [Prefetch] produced.
 *
 * This is the "boost by business rules" layer: multiply the vector score by a popularity field, add a
 * bonus for points matching a condition, decay by recency or by distance. It runs on the server, over
 * candidates that have already been narrowed, which is what makes it cheap enough to be worth doing.
 *
 * ```kotlin
 * // relevance, boosted for in-stock items and decayed by how far away the seller is
 * qdrant.search("products") {
 *     prefetch { query(queryVector); limit = 100 }
 *     formula(
 *         Expression.sum(
 *             Expression.score,
 *             Expression.mult(Expression.of(0.5), Expression.condition(filter { must { "in_stock" eq true } })),
 *             Expression.expDecay(Expression.geoDistance(origin = here, to = "seller_location"), scale = 5_000.0),
 *         ),
 *     )
 *     limit = 10
 * }
 * ```
 *
 * Request-only: Qdrant never returns an expression, so this serializes but does not deserialize.
 */
@Serializable(with = ExpressionSerializer::class)
public sealed interface Expression {

    /** A constant. */
    public data class Value(public val value: Double) : Expression

    /**
     * A payload key, or one of Qdrant's variables. `$score` is the vector score of the candidate;
     * a plain key reads that payload field, and a missing field evaluates to the formula's default
     * for that key (see `defaults`) or fails the query.
     */
    public data class Variable(public val name: String) : Expression

    /** A filter condition as a number: 1.0 when the point matches, 0.0 when it does not. */
    public data class Condition(public val condition: dev.kdrant.model.Condition) : Expression

    /** Distance in metres from [origin] to the geo point in the payload field [to]. */
    public data class GeoDistance(public val origin: GeoPoint, public val to: String) : Expression

    /** An RFC 3339 datetime as a Unix timestamp. */
    public data class Datetime(public val value: String) : Expression

    /** A payload key holding a datetime, as a Unix timestamp. */
    public data class DatetimeKey(public val key: String) : Expression

    /** The product of every operand. */
    public data class Mult(public val operands: List<Expression>) : Expression

    /** The sum of every operand. */
    public data class Sum(public val operands: List<Expression>) : Expression

    /** Arithmetic negation. */
    public data class Neg(public val operand: Expression) : Expression

    /** Absolute value. */
    public data class Abs(public val operand: Expression) : Expression

    /** Square root. */
    public data class Sqrt(public val operand: Expression) : Expression

    /** Natural exponential. */
    public data class Exp(public val operand: Expression) : Expression

    /** Base-10 logarithm. */
    public data class Log10(public val operand: Expression) : Expression

    /** Natural logarithm. */
    public data class Ln(public val operand: Expression) : Expression

    /**
     * [left] divided by [right]. [byZeroDefault] is what the division evaluates to when [right] is
     * zero; without it Qdrant fails the query rather than inventing a number.
     */
    public data class Div(
        public val left: Expression,
        public val right: Expression,
        public val byZeroDefault: Double? = null,
    ) : Expression

    /** [base] raised to [exponent]. */
    public data class Pow(public val base: Expression, public val exponent: Expression) : Expression

    /** Linear decay of [params]. */
    public data class LinDecay(public val params: DecayParams) : Expression

    /** Exponential decay of [params]. */
    public data class ExpDecay(public val params: DecayParams) : Expression

    /** Gaussian decay of [params]. */
    public data class GaussDecay(public val params: DecayParams) : Expression

    public companion object {
        /** The candidate's vector score. */
        public val score: Expression = Variable("\$score")

        public fun of(value: Number): Expression = Value(value.toDouble())

        /** A payload key, e.g. `popularity` or a nested `seller.rating`. */
        public fun key(name: String): Expression = Variable(name)

        public fun condition(filter: Filter): Expression =
            Condition(dev.kdrant.model.Condition.Sub(filter))

        public fun geoDistance(origin: GeoPoint, to: String): Expression = GeoDistance(origin, to)

        public fun mult(vararg operands: Expression): Expression = Mult(operands.toList())

        public fun sum(vararg operands: Expression): Expression = Sum(operands.toList())

        /**
         * Decay [x] towards zero as it moves away from [target], reaching [midpoint] at a distance of
         * [scale]. The classic use is recency: `x` is a datetime key, `scale` a number of seconds.
         */
        public fun expDecay(
            x: Expression,
            target: Expression? = null,
            scale: Double? = null,
            midpoint: Double? = null,
        ): Expression = ExpDecay(DecayParams(x, target, scale, midpoint))

        /** As [expDecay], with a linear curve. */
        public fun linDecay(
            x: Expression,
            target: Expression? = null,
            scale: Double? = null,
            midpoint: Double? = null,
        ): Expression = LinDecay(DecayParams(x, target, scale, midpoint))

        /** As [expDecay], with a Gaussian curve. */
        public fun gaussDecay(
            x: Expression,
            target: Expression? = null,
            scale: Double? = null,
            midpoint: Double? = null,
        ): Expression = GaussDecay(DecayParams(x, target, scale, midpoint))
    }
}

/**
 * Shape of a decay curve. The output is 1.0 at [target] and [midpoint] once [x] is [scale] away
 * from it.
 *
 * @property scale must be a positive, non-zero number; Qdrant rejects anything else.
 * @property midpoint between 0 and 1; defaults to 0.5.
 */
public data class DecayParams(
    public val x: Expression,
    public val target: Expression? = null,
    public val scale: Double? = null,
    public val midpoint: Double? = null,
) {
    init {
        scale?.let { require(it > 0) { "a decay scale must be a positive non-zero number, was $it" } }
        midpoint?.let { require(it in 0.0..1.0) { "a decay midpoint must be in 0..1, was $it" } }
    }
}

/**
 * Maximal Marginal Relevance reranking, applied after a nearest search using the same query vector.
 *
 * It trades relevance for variety: without it, ten results about the same thing all rank highly, which
 * is usually not what someone searching wanted to read.
 *
 * @property diversity 0 favours relevance, 1 favours dissimilarity between results; Qdrant defaults to 0.5.
 * @property candidatesLimit how many candidates to rerank; the query's `limit` when null.
 */
@Serializable
public data class Mmr(
    @SerialName("diversity") public val diversity: Float? = null,
    @SerialName("candidates_limit") public val candidatesLimit: Int? = null,
) {
    init {
        diversity?.let { require(it in 0f..1f) { "mmr diversity must be in 0..1, was $it" } }
        candidatesLimit?.let { require(it > 0) { "mmr candidatesLimit must be > 0, was $it" } }
    }
}

internal object ExpressionSerializer : KSerializer<Expression> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.Expression", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Expression) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Expression requires JSON")
        json.encodeJsonElement(toElement(json.json, value))
    }

    override fun deserialize(decoder: Decoder): Expression =
        throw SerializationException("formula expressions are request-only; Qdrant never returns one")

    internal fun toElement(json: Json, value: Expression): JsonElement = when (value) {
        is Expression.Value -> JsonPrimitive(value.value)
        is Expression.Variable -> JsonPrimitive(value.name)
        is Expression.Condition -> json.encodeToJsonElement(Condition.serializer(), value.condition)
        is Expression.GeoDistance -> wrap("geo_distance") {
            put("origin", json.encodeToJsonElement(GeoPoint.serializer(), value.origin))
            put("to", value.to)
        }
        is Expression.Datetime -> buildJsonObject { put("datetime", value.value) }
        is Expression.DatetimeKey -> buildJsonObject { put("datetime_key", value.key) }
        is Expression.Mult -> buildJsonObject {
            put("mult", JsonArray(value.operands.map { toElement(json, it) }))
        }
        is Expression.Sum -> buildJsonObject {
            put("sum", JsonArray(value.operands.map { toElement(json, it) }))
        }
        is Expression.Neg -> buildJsonObject { put("neg", toElement(json, value.operand)) }
        is Expression.Abs -> buildJsonObject { put("abs", toElement(json, value.operand)) }
        is Expression.Sqrt -> buildJsonObject { put("sqrt", toElement(json, value.operand)) }
        is Expression.Exp -> buildJsonObject { put("exp", toElement(json, value.operand)) }
        is Expression.Log10 -> buildJsonObject { put("log10", toElement(json, value.operand)) }
        is Expression.Ln -> buildJsonObject { put("ln", toElement(json, value.operand)) }
        is Expression.Div -> wrap("div") {
            put("left", toElement(json, value.left))
            put("right", toElement(json, value.right))
            value.byZeroDefault?.let { put("by_zero_default", it) }
        }
        is Expression.Pow -> wrap("pow") {
            put("base", toElement(json, value.base))
            put("exponent", toElement(json, value.exponent))
        }
        is Expression.LinDecay -> wrap("lin_decay") { decay(json, value.params) }
        is Expression.ExpDecay -> wrap("exp_decay") { decay(json, value.params) }
        is Expression.GaussDecay -> wrap("gauss_decay") { decay(json, value.params) }
    }

    private fun wrap(key: String, build: JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put(key, buildJsonObject(build)) }

    private fun JsonObjectBuilder.decay(json: Json, params: DecayParams) {
        put("x", toElement(json, params.x))
        params.target?.let { put("target", toElement(json, it)) }
        params.scale?.let { put("scale", it) }
        params.midpoint?.let { put("midpoint", it) }
    }
}
