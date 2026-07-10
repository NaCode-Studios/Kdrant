package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A single filter condition. Most conditions target a payload [Field] with a [FieldMatcher];
 * the rest cover Qdrant's special conditions (`is_empty`, `is_null`, `has_id`, `has_vector`,
 * `nested`) and recursive nesting of a whole [Filter] via [Sub].
 *
 * Conditions are write-only: Qdrant never returns them, so the serializer does not implement
 * deserialization.
 */
@Serializable(with = ConditionSerializer::class)
public sealed interface Condition {

    /** A condition on a payload field: `{"key": ..., <matcher>}`. */
    public data class Field(public val key: String, public val matcher: FieldMatcher) : Condition

    /** `{"is_empty": {"key": ...}}` — field missing, null, or empty array. */
    public data class IsEmpty(public val key: String) : Condition

    /** `{"is_null": {"key": ...}}` — field present but null. */
    public data class IsNull(public val key: String) : Condition

    /** `{"has_id": [...]}` — point id is one of the given ids. */
    public data class HasId(public val ids: List<PointId>) : Condition

    /** `{"has_vector": "name"}` — the named vector is present (`""` for the anonymous vector). */
    public data class HasVector(public val name: String) : Condition

    /** `{"nested": {"key": ..., "filter": {...}}}` — sub-filter evaluated per array element. */
    public data class Nested(public val key: String, public val filter: Filter) : Condition

    /** A nested boolean sub-filter, serialized as a plain [Filter] object. */
    public data class Sub(public val filter: Filter) : Condition
}

internal object ConditionSerializer : KSerializer<Condition> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.Condition")

    override fun serialize(encoder: Encoder, value: Condition) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("Condition can only be serialized to JSON")
        jsonEncoder.encodeJsonElement(toJson(value, jsonEncoder.json))
    }

    override fun deserialize(decoder: Decoder): Condition =
        throw SerializationException(
            "Kdrant filters are request-only: Qdrant never returns them, so Condition/Filter cannot be deserialized",
        )

    private fun toJson(condition: Condition, json: Json): JsonObject = when (condition) {
        is Condition.Field -> buildJsonObject {
            put("key", condition.key)
            appendMatcher(condition.matcher, json)
        }
        is Condition.IsEmpty -> buildJsonObject {
            put("is_empty", buildJsonObject { put("key", condition.key) })
        }
        is Condition.IsNull -> buildJsonObject {
            put("is_null", buildJsonObject { put("key", condition.key) })
        }
        is Condition.HasId -> buildJsonObject {
            put("has_id", JsonArray(condition.ids.map { json.encodeToJsonElement(PointId.serializer(), it) }))
        }
        is Condition.HasVector -> buildJsonObject {
            put("has_vector", condition.name)
        }
        is Condition.Nested -> buildJsonObject {
            put(
                "nested",
                buildJsonObject {
                    put("key", condition.key)
                    put("filter", json.encodeToJsonElement(Filter.serializer(), condition.filter))
                },
            )
        }
        is Condition.Sub -> json.encodeToJsonElement(Filter.serializer(), condition.filter) as JsonObject
    }

    private fun JsonObjectBuilder.appendMatcher(matcher: FieldMatcher, json: Json) {
        when (matcher) {
            is FieldMatcher.Match ->
                put("match", buildJsonObject { put("value", matcher.value) })
            is FieldMatcher.MatchAny ->
                put("match", buildJsonObject { put("any", JsonArray(matcher.values)) })
            is FieldMatcher.MatchExcept ->
                put("match", buildJsonObject { put("except", JsonArray(matcher.values)) })
            is FieldMatcher.MatchText ->
                put("match", buildJsonObject { put("text", matcher.text) })
            is FieldMatcher.MatchTextAny ->
                put("match", buildJsonObject { put("text_any", matcher.text) })
            is FieldMatcher.MatchPhrase ->
                put("match", buildJsonObject { put("phrase", matcher.text) })
            is FieldMatcher.Range ->
                put("range", json.encodeToJsonElement(FieldMatcher.Range.serializer(), matcher))
            is FieldMatcher.DatetimeRange ->
                put("range", json.encodeToJsonElement(FieldMatcher.DatetimeRange.serializer(), matcher))
            is FieldMatcher.ValuesCount ->
                put("values_count", json.encodeToJsonElement(FieldMatcher.ValuesCount.serializer(), matcher))
            is FieldMatcher.GeoBoundingBox ->
                put("geo_bounding_box", json.encodeToJsonElement(FieldMatcher.GeoBoundingBox.serializer(), matcher))
            is FieldMatcher.GeoRadius ->
                put("geo_radius", json.encodeToJsonElement(FieldMatcher.GeoRadius.serializer(), matcher))
            is FieldMatcher.GeoPolygon ->
                put("geo_polygon", geoPolygonJson(matcher, json))
        }
    }

    private fun geoPolygonJson(polygon: FieldMatcher.GeoPolygon, json: Json): JsonObject = buildJsonObject {
        put("exterior", ringJson(polygon.exterior, json))
        if (polygon.interiors.isNotEmpty()) {
            put("interiors", JsonArray(polygon.interiors.map { ringJson(it, json) }))
        }
    }

    private fun ringJson(points: List<GeoPoint>, json: Json): JsonObject = buildJsonObject {
        put("points", JsonArray(points.map { json.encodeToJsonElement(GeoPoint.serializer(), it) }))
    }
}
