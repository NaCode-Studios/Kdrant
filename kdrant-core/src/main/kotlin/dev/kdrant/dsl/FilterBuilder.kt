package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Condition
import dev.kdrant.model.FieldMatcher
import dev.kdrant.model.Filter
import dev.kdrant.model.GeoPoint
import dev.kdrant.model.MinShould
import dev.kdrant.model.PointId
import kotlinx.serialization.json.JsonPrimitive

/** Builds a [Filter] from the given clauses. */
public fun filter(configure: FilterBuilder.() -> Unit): Filter =
    FilterBuilder().apply(configure).build()

/** DSL for a [Filter]'s four clauses: [must], [should], [mustNot], [minShould]. */
@KdrantDsl
public class FilterBuilder {
    private var must: MutableList<Condition>? = null
    private var should: MutableList<Condition>? = null
    private var mustNot: MutableList<Condition>? = null
    private var minShould: MinShould? = null

    /** All listed conditions must match (AND). Repeatable — conditions accumulate. */
    public fun must(configure: ClauseBuilder.() -> Unit) {
        must = (must ?: mutableListOf()).apply { addAll(ClauseBuilder().apply(configure).build()) }
    }

    /** At least one listed condition must match (OR). Repeatable — conditions accumulate. */
    public fun should(configure: ClauseBuilder.() -> Unit) {
        should = (should ?: mutableListOf()).apply { addAll(ClauseBuilder().apply(configure).build()) }
    }

    /** No listed condition may match (NOR). Repeatable — conditions accumulate. */
    public fun mustNot(configure: ClauseBuilder.() -> Unit) {
        mustNot = (mustNot ?: mutableListOf()).apply { addAll(ClauseBuilder().apply(configure).build()) }
    }

    /** At least [minCount] of the listed conditions must match. May be set once per filter. */
    public fun minShould(minCount: Int, configure: ClauseBuilder.() -> Unit) {
        check(minShould == null) { "minShould can only be set once per filter" }
        minShould = MinShould(ClauseBuilder().apply(configure).build(), minCount)
    }

    internal fun build(): Filter = Filter(
        must = must,
        should = should,
        mustNot = mustNot,
        minShould = minShould,
    )
}

/**
 * DSL for the conditions inside a filter clause. Offers both function-style
 * (`match("city", "London")`, `range("price", gte = 100.0)`) and infix-style
 * (`"city" eq "London"`, `"price" between 100.0..450.0`) entry points.
 */
@KdrantDsl
public class ClauseBuilder {
    private val conditions: MutableList<Condition> = mutableListOf()

    private fun add(condition: Condition) {
        conditions += condition
    }

    // --- Exact match ---------------------------------------------------------------------------

    /** Exact value match. [value] must be a String, Number or Boolean. */
    public fun match(key: String, value: Any) {
        add(Condition.Field(key, FieldMatcher.Match(scalar(value))))
    }

    /** Infix form of [match]. */
    public infix fun String.eq(value: Any): Unit = match(this, value)

    // --- Set membership ------------------------------------------------------------------------

    /** Value-in-set (`any`). Each value must be a String, Number or Boolean. */
    public fun matchAny(key: String, values: Collection<Any>) {
        require(values.isNotEmpty()) { "matchAny on '$key' needs at least one value" }
        add(Condition.Field(key, FieldMatcher.MatchAny(values.map(::scalar))))
    }

    /** Value-in-set (`any`). */
    public fun matchAny(key: String, vararg values: Any): Unit = matchAny(key, values.toList())

    /** Value-not-in-set (`except`). */
    public fun matchExcept(key: String, values: Collection<Any>) {
        require(values.isNotEmpty()) { "matchExcept on '$key' needs at least one value" }
        add(Condition.Field(key, FieldMatcher.MatchExcept(values.map(::scalar))))
    }

    /** Value-not-in-set (`except`). */
    public fun matchExcept(key: String, vararg values: Any): Unit = matchExcept(key, values.toList())

    // --- Full text -----------------------------------------------------------------------------

    /** Full-text match: all tokens present. */
    public fun matchText(key: String, text: String) {
        add(Condition.Field(key, FieldMatcher.MatchText(text)))
    }

    /** Full-text match: at least one token present. */
    public fun matchTextAny(key: String, text: String) {
        add(Condition.Field(key, FieldMatcher.MatchTextAny(text)))
    }

    /** Exact phrase match (order matters). */
    public fun matchPhrase(key: String, text: String) {
        add(Condition.Field(key, FieldMatcher.MatchPhrase(text)))
    }

    // --- Numeric range -------------------------------------------------------------------------

    /** Numeric range condition; provide any subset of bounds. */
    public fun range(
        key: String,
        gt: Number? = null,
        gte: Number? = null,
        lt: Number? = null,
        lte: Number? = null,
    ) {
        require(gt != null || gte != null || lt != null || lte != null) {
            "range on '$key' needs at least one bound (gt/gte/lt/lte)"
        }
        add(
            Condition.Field(
                key,
                FieldMatcher.Range(gt?.toDouble(), gte?.toDouble(), lt?.toDouble(), lte?.toDouble()),
            ),
        )
    }

    public infix fun String.gt(value: Number): Unit = range(this, gt = value)
    public infix fun String.gte(value: Number): Unit = range(this, gte = value)
    public infix fun String.lt(value: Number): Unit = range(this, lt = value)
    public infix fun String.lte(value: Number): Unit = range(this, lte = value)

    /** Inclusive numeric range, e.g. `"price" between 100.0..450.0`. */
    public infix fun String.between(bounds: ClosedFloatingPointRange<Double>): Unit =
        range(this, gte = bounds.start, lte = bounds.endInclusive)

    // --- Datetime range (RFC 3339) -------------------------------------------------------------

    /** RFC 3339 datetime range condition; provide any subset of bounds. */
    public fun datetimeRange(
        key: String,
        gt: String? = null,
        gte: String? = null,
        lt: String? = null,
        lte: String? = null,
    ) {
        require(gt != null || gte != null || lt != null || lte != null) {
            "datetimeRange on '$key' needs at least one bound (gt/gte/lt/lte)"
        }
        add(Condition.Field(key, FieldMatcher.DatetimeRange(gt, gte, lt, lte)))
    }

    // --- Values count --------------------------------------------------------------------------

    /** Condition on the number of values in an array field. */
    public fun valuesCount(
        key: String,
        gt: Int? = null,
        gte: Int? = null,
        lt: Int? = null,
        lte: Int? = null,
    ) {
        require(gt != null || gte != null || lt != null || lte != null) {
            "valuesCount on '$key' needs at least one bound (gt/gte/lt/lte)"
        }
        add(Condition.Field(key, FieldMatcher.ValuesCount(gt, gte, lt, lte)))
    }

    // --- Geo -----------------------------------------------------------------------------------

    /** Points within the given bounding box. */
    public fun geoBoundingBox(key: String, topLeft: GeoPoint, bottomRight: GeoPoint) {
        add(Condition.Field(key, FieldMatcher.GeoBoundingBox(topLeft, bottomRight)))
    }

    /** Points within [radius] metres of [center]. */
    public fun geoRadius(key: String, center: GeoPoint, radius: Double) {
        add(Condition.Field(key, FieldMatcher.GeoRadius(center, radius)))
    }

    /** Points inside [exterior] and outside every ring in [interiors]. */
    public fun geoPolygon(
        key: String,
        exterior: List<GeoPoint>,
        interiors: List<List<GeoPoint>> = emptyList(),
    ) {
        add(Condition.Field(key, FieldMatcher.GeoPolygon(exterior, interiors)))
    }

    // --- Special conditions --------------------------------------------------------------------

    /** Field missing, null, or an empty array. */
    public fun isEmpty(key: String): Unit = add(Condition.IsEmpty(key))

    /** Field present but null. */
    public fun isNull(key: String): Unit = add(Condition.IsNull(key))

    /** Point id is one of [ids]. */
    public fun hasId(ids: List<PointId>) {
        require(ids.isNotEmpty()) { "hasId needs at least one id" }
        add(Condition.HasId(ids))
    }

    /** Point id is one of [ids]. */
    public fun hasId(vararg ids: PointId): Unit = hasId(ids.toList())

    /** The named vector is present (`""` for the anonymous vector). */
    public fun hasVector(name: String): Unit = add(Condition.HasVector(name))

    /** Sub-filter evaluated per element of the array field [key]. */
    public fun nested(key: String, configure: FilterBuilder.() -> Unit): Unit =
        add(Condition.Nested(key, FilterBuilder().apply(configure).build()))

    /** A nested boolean sub-filter as a single condition (for grouping / de Morgan expressions). */
    public fun filter(configure: FilterBuilder.() -> Unit): Unit =
        add(Condition.Sub(FilterBuilder().apply(configure).build()))

    internal fun build(): List<Condition> = conditions.toList()
}

private fun scalar(value: Any): JsonPrimitive = when (value) {
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> throw IllegalArgumentException(
        "match values must be String, Number or Boolean; got ${value::class.simpleName}: $value",
    )
}
