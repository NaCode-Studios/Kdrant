package dev.kdrant.langchain4j

import dev.kdrant.model.Condition
import dev.kdrant.model.FieldMatcher
import dev.langchain4j.store.embedding.filter.comparison.ContainsString
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsIn
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn
import dev.langchain4j.store.embedding.filter.logical.And
import dev.langchain4j.store.embedding.filter.logical.Not
import dev.langchain4j.store.embedding.filter.logical.Or
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import dev.kdrant.model.Filter as KdrantFilter
import dev.langchain4j.store.embedding.filter.Filter as LangChain4jFilter

/**
 * Translate a LangChain4j metadata [filter][LangChain4jFilter] into Kdrant's [KdrantFilter], so a filtered
 * LangChain4j retriever keeps its filters when it swaps in [KdrantEmbeddingStore].
 *
 * ```kotlin
 * val filter = metadataKey("lang").isEqualTo("en").and(metadataKey("year").isGreaterThan(2020))
 * val kdrantFilter = filter.toKdrantFilter()
 * ```
 *
 * The mapping, operator by operator:
 *
 * | LangChain4j                          | Qdrant                                  |
 * |--------------------------------------|-----------------------------------------|
 * | `And`, `Or`                          | `must`, `should` (chains are flattened) |
 * | `Not`                                | `must_not`                              |
 * | `IsEqualTo`, `IsNotEqualTo`          | `match.value`, negated `match.value`    |
 * | `IsGreaterThan` and the other three  | `range` (numbers) or `range` on RFC 3339 strings |
 * | `IsIn`, `IsNotIn`                    | `match.any`, `match.except`             |
 * | `ContainsString`                     | `match.text` — a full-text match, so the key needs a text payload index |
 *
 * @throws IllegalArgumentException if a comparison value is not a String, Number, Boolean or UUID.
 */
public fun LangChain4jFilter.toKdrantFilter(): KdrantFilter = when (this) {
    is And -> KdrantFilter(must = flattenAnd())
    is Or -> KdrantFilter(should = flattenOr())
    is Not -> KdrantFilter(mustNot = listOf(expression().toCondition()))
    else -> KdrantFilter(must = listOf(toCondition()))
}

/**
 * Collect the operands of a chain of same-type boolean filters into one flat condition list, so
 * `a.and(b).and(c)` becomes a three-element `must` rather than nested sub-filters.
 */
private fun And.flattenAnd(): List<Condition> =
    listOf(left(), right()).flatMap { if (it is And) it.flattenAnd() else listOf(it.toCondition()) }

private fun Or.flattenOr(): List<Condition> =
    listOf(left(), right()).flatMap { if (it is Or) it.flattenOr() else listOf(it.toCondition()) }

private fun LangChain4jFilter.toCondition(): Condition = when (this) {
    is And, is Or, is Not -> Condition.Sub(toKdrantFilter())

    is IsEqualTo -> Condition.Field(key(), FieldMatcher.Match(scalar(comparisonValue())))
    is IsNotEqualTo -> negate(Condition.Field(key(), FieldMatcher.Match(scalar(comparisonValue()))))
    is IsGreaterThan -> Condition.Field(key(), range(comparisonValue(), Bound.GT))
    is IsGreaterThanOrEqualTo -> Condition.Field(key(), range(comparisonValue(), Bound.GTE))
    is IsLessThan -> Condition.Field(key(), range(comparisonValue(), Bound.LT))
    is IsLessThanOrEqualTo -> Condition.Field(key(), range(comparisonValue(), Bound.LTE))
    is IsIn -> Condition.Field(key(), FieldMatcher.MatchAny(scalars(key(), comparisonValues())))
    is IsNotIn -> Condition.Field(key(), FieldMatcher.MatchExcept(scalars(key(), comparisonValues())))
    is ContainsString -> Condition.Field(key(), FieldMatcher.MatchText(comparisonValue()))

    else -> throw IllegalArgumentException(
        "kdrant-langchain4j cannot translate ${this::class.java.name}; it is not part of the LangChain4j filter model",
    )
}

private fun negate(condition: Condition): Condition = Condition.Sub(KdrantFilter(mustNot = listOf(condition)))

private enum class Bound { GT, GTE, LT, LTE }

/**
 * Qdrant splits ordering by value type: numbers go through `range`, RFC 3339 timestamps through the
 * datetime variant of the same block. LangChain4j carries both as a plain [Comparable], so the variant
 * is chosen here from the runtime type.
 */
private fun range(value: Comparable<*>?, bound: Bound): FieldMatcher = when (value) {
    is Number -> value.toDouble().let {
        when (bound) {
            Bound.GT -> FieldMatcher.Range(gt = it)
            Bound.GTE -> FieldMatcher.Range(gte = it)
            Bound.LT -> FieldMatcher.Range(lt = it)
            Bound.LTE -> FieldMatcher.Range(lte = it)
        }
    }
    is String -> when (bound) {
        Bound.GT -> FieldMatcher.DatetimeRange(gt = value)
        Bound.GTE -> FieldMatcher.DatetimeRange(gte = value)
        Bound.LT -> FieldMatcher.DatetimeRange(lt = value)
        Bound.LTE -> FieldMatcher.DatetimeRange(lte = value)
    }
    else -> throw IllegalArgumentException(
        "a range comparison needs a Number or an RFC 3339 datetime String, got ${describe(value)}",
    )
}

private fun scalars(key: String, values: Collection<*>): List<JsonPrimitive> {
    require(values.isNotEmpty()) { "a set-membership filter on '$key' needs at least one value" }
    return values.map(::scalar)
}

private fun scalar(value: Any?): JsonPrimitive = when (value) {
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    // LangChain4j metadata carries UUIDs as a first-class type; Qdrant payload holds them as strings.
    is UUID -> JsonPrimitive(value.toString())
    else -> throw IllegalArgumentException(
        "filter values must be String, Number, Boolean or UUID; got ${describe(value)}",
    )
}

private fun describe(value: Any?): String =
    if (value == null) "null" else "${value::class.java.simpleName}: $value"
