package dev.kdrant.springai

import dev.kdrant.model.Condition
import dev.kdrant.model.FieldMatcher
import kotlinx.serialization.json.JsonPrimitive
import dev.kdrant.model.Filter as KdrantFilter
import org.springframework.ai.vectorstore.filter.Filter as SpringAiFilter

/**
 * Translate a Spring AI metadata-filter [expression][SpringAiFilter.Expression] into Kdrant's [KdrantFilter],
 * so a filtered Spring AI application keeps its filters when it swaps in [KdrantVectorStore].
 *
 * ```kotlin
 * val expression = FilterExpressionTextParser().parse("lang == 'en' && year >= 2020")
 * val filter = expression.toKdrantFilter()
 * ```
 *
 * The mapping, operator by operator:
 *
 * | Spring AI    | Qdrant                                        |
 * |--------------|-----------------------------------------------|
 * | `AND`, `OR`  | `must`, `should` (chains are flattened)        |
 * | `NOT`        | `must_not`                                    |
 * | `==`, `!=`   | `match.value`, negated `match.value`          |
 * | `>` `>=` `<` `<=` | `range` (numbers) or `range` on RFC 3339 strings |
 * | `IN`, `NIN`  | `match.any`, `match.except`                   |
 * | `IS NULL`    | `is_empty` — Qdrant's `is_null` matches only a present-but-null field, while Spring AI's `IS NULL` also covers a missing key |
 * | `IS NOT NULL`| negated `is_empty`                            |
 *
 * @throws IllegalArgumentException if an operand is not the key/value shape the operator requires, or a
 *   comparison value is not a String, Number or Boolean.
 */
public fun SpringAiFilter.Expression.toKdrantFilter(): KdrantFilter = when (type) {
    SpringAiFilter.ExpressionType.AND -> KdrantFilter(must = flatten(SpringAiFilter.ExpressionType.AND))
    SpringAiFilter.ExpressionType.OR -> KdrantFilter(should = flatten(SpringAiFilter.ExpressionType.OR))
    SpringAiFilter.ExpressionType.NOT -> KdrantFilter(mustNot = listOf(conditionOf(left)))
    else -> KdrantFilter(must = listOf(toCondition()))
}

/**
 * Collect the operands of a chain of same-type boolean expressions into one flat condition list, so
 * `a && b && c` becomes a three-element `must` rather than nested sub-filters. A parenthesised group
 * is a leaf here: flattening stops at it, which is what keeps `(a || b) && c` correct.
 */
private fun SpringAiFilter.Expression.flatten(type: SpringAiFilter.ExpressionType): List<Condition> =
    listOf(left, right).flatMap { operand ->
        if (operand is SpringAiFilter.Expression && operand.type == type) {
            operand.flatten(type)
        } else {
            listOf(conditionOf(operand))
        }
    }

private fun conditionOf(operand: SpringAiFilter.Operand?): Condition = when (operand) {
    is SpringAiFilter.Group -> conditionOf(operand.content())
    is SpringAiFilter.Expression -> operand.toCondition()
    else -> throw IllegalArgumentException(
        "expected a filter expression or group, got ${describe(operand)}",
    )
}

private fun SpringAiFilter.Expression.toCondition(): Condition = when (type) {
    SpringAiFilter.ExpressionType.AND,
    SpringAiFilter.ExpressionType.OR,
    SpringAiFilter.ExpressionType.NOT,
    -> Condition.Sub(toKdrantFilter())

    SpringAiFilter.ExpressionType.EQ -> Condition.Field(key(), FieldMatcher.Match(scalar(value())))
    SpringAiFilter.ExpressionType.NE -> negate(Condition.Field(key(), FieldMatcher.Match(scalar(value()))))
    SpringAiFilter.ExpressionType.GT -> Condition.Field(key(), range(value(), Bound.GT))
    SpringAiFilter.ExpressionType.GTE -> Condition.Field(key(), range(value(), Bound.GTE))
    SpringAiFilter.ExpressionType.LT -> Condition.Field(key(), range(value(), Bound.LT))
    SpringAiFilter.ExpressionType.LTE -> Condition.Field(key(), range(value(), Bound.LTE))
    SpringAiFilter.ExpressionType.IN -> Condition.Field(key(), FieldMatcher.MatchAny(values()))
    SpringAiFilter.ExpressionType.NIN -> Condition.Field(key(), FieldMatcher.MatchExcept(values()))
    SpringAiFilter.ExpressionType.ISNULL -> Condition.IsEmpty(key())
    SpringAiFilter.ExpressionType.ISNOTNULL -> negate(Condition.IsEmpty(key()))
}

private fun negate(condition: Condition): Condition = Condition.Sub(KdrantFilter(mustNot = listOf(condition)))

private fun SpringAiFilter.Expression.key(): String {
    val operand = left
    require(operand is SpringAiFilter.Key) { "$type expects a metadata key on the left, got ${describe(operand)}" }
    return operand.key()
}

private fun SpringAiFilter.Expression.value(): Any? {
    val operand = right
    require(operand is SpringAiFilter.Value) { "$type expects a value on the right, got ${describe(operand)}" }
    return operand.value()
}

private fun SpringAiFilter.Expression.values(): List<JsonPrimitive> {
    val raw = value()
    val items = when (raw) {
        is Collection<*> -> raw.toList()
        is Array<*> -> raw.toList()
        else -> listOf(raw)
    }
    require(items.isNotEmpty()) { "$type on '${key()}' needs at least one value" }
    return items.map(::scalar)
}

private enum class Bound { GT, GTE, LT, LTE }

/**
 * Qdrant splits ordering by value type: numbers go through `range`, RFC 3339 timestamps through the
 * datetime variant of the same block. Spring AI carries both as a plain comparison value, so the
 * variant is chosen here from the runtime type.
 */
private fun range(value: Any?, bound: Bound): FieldMatcher = when (value) {
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

private fun scalar(value: Any?): JsonPrimitive = when (value) {
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> throw IllegalArgumentException(
        "filter values must be String, Number or Boolean; got ${describe(value)}",
    )
}

private fun describe(value: Any?): String =
    if (value == null) "null" else "${value::class.java.simpleName}: $value"
