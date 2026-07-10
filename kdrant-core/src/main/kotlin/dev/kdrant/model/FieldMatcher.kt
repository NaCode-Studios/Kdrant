package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * The matcher applied to a payload field inside a [Condition.Field]. Each variant maps to exactly
 * one Qdrant condition block (`match`, `range`, `values_count`, `geo_*`), mirroring the Qdrant
 * filter model.
 */
public sealed interface FieldMatcher {

    /** Exact value match (`match.value`). */
    public data class Match(public val value: JsonPrimitive) : FieldMatcher

    /** Value-in-set match (`match.any`). */
    public data class MatchAny(public val values: List<JsonPrimitive>) : FieldMatcher

    /** Value-not-in-set match (`match.except`). */
    public data class MatchExcept(public val values: List<JsonPrimitive>) : FieldMatcher

    /** Full-text match, all tokens present (`match.text`). */
    public data class MatchText(public val text: String) : FieldMatcher

    /** Full-text match, any token present (`match.text_any`). */
    public data class MatchTextAny(public val text: String) : FieldMatcher

    /** Exact phrase match (`match.phrase`). */
    public data class MatchPhrase(public val text: String) : FieldMatcher

    /** Numeric range (`range`). */
    @Serializable
    public data class Range(
        @SerialName("gt") public val gt: Double? = null,
        @SerialName("gte") public val gte: Double? = null,
        @SerialName("lt") public val lt: Double? = null,
        @SerialName("lte") public val lte: Double? = null,
    ) : FieldMatcher

    /** RFC 3339 datetime range (`range` with string bounds). */
    @Serializable
    public data class DatetimeRange(
        @SerialName("gt") public val gt: String? = null,
        @SerialName("gte") public val gte: String? = null,
        @SerialName("lt") public val lt: String? = null,
        @SerialName("lte") public val lte: String? = null,
    ) : FieldMatcher

    /** Count of values in an array field (`values_count`). */
    @Serializable
    public data class ValuesCount(
        @SerialName("gt") public val gt: Int? = null,
        @SerialName("gte") public val gte: Int? = null,
        @SerialName("lt") public val lt: Int? = null,
        @SerialName("lte") public val lte: Int? = null,
    ) : FieldMatcher

    /** Geographic bounding box (`geo_bounding_box`). */
    @Serializable
    public data class GeoBoundingBox(
        @SerialName("top_left") public val topLeft: GeoPoint,
        @SerialName("bottom_right") public val bottomRight: GeoPoint,
    ) : FieldMatcher

    /** Geographic radius in metres (`geo_radius`). */
    @Serializable
    public data class GeoRadius(
        @SerialName("center") public val center: GeoPoint,
        @SerialName("radius") public val radius: Double,
    ) : FieldMatcher

    /**
     * Geographic polygon (`geo_polygon`) with an exterior ring and optional interior holes.
     * Each ring's first and last point must coincide (enforced by Qdrant, not here).
     */
    public data class GeoPolygon(
        public val exterior: List<GeoPoint>,
        public val interiors: List<List<GeoPoint>> = emptyList(),
    ) : FieldMatcher
}
