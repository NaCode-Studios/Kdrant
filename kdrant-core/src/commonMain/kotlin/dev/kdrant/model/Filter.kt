package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Qdrant filter: four independent, combinable clauses. Each holds a list of [Condition]s and
 * clauses can nest recursively (a condition can itself be a whole [Filter] — see [Condition.Sub]),
 * so any boolean expression is expressible.
 *
 * Request-only: filters are sent to Qdrant but never returned, so this type is serialized but does
 * not round-trip (its [Condition]s cannot be deserialized).
 */
@Serializable
public data class Filter(
    /** All conditions must match (AND). */
    @SerialName("must") public val must: List<Condition>? = null,

    /** At least one condition must match (OR). */
    @SerialName("should") public val should: List<Condition>? = null,

    /** No condition may match (NOR). */
    @SerialName("must_not") public val mustNot: List<Condition>? = null,

    /** At least [MinShould.minCount] of the given conditions must match. */
    @SerialName("min_should") public val minShould: MinShould? = null,
)

/** The `min_should` clause: at least [minCount] of [conditions] must match. */
@Serializable
public data class MinShould(
    @SerialName("conditions") public val conditions: List<Condition>,
    @SerialName("min_count") public val minCount: Int,
)
