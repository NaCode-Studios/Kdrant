package dev.kdrant.transport.grpc

import com.google.protobuf.Timestamp
import dev.kdrant.model.Condition
import dev.kdrant.model.FieldMatcher
import dev.kdrant.model.Filter
import dev.kdrant.model.GeoPoint
import dev.kdrant.model.MinShould
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import qdrant.Common
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Filter conversion between Kdrant's models and Qdrant's protobuf messages.
 *
 * REST filters are write-only — Qdrant never echoes one back, so `ConditionSerializer` only serializes.
 * That leaves this mapping with no round trip to be checked against, which is why [toModel] exists at
 * all: nothing in the engine reads a filter off the wire, but a table of 7 condition types and 12
 * matchers is exactly the kind of code where a swapped field goes unnoticed for a release. A reverse
 * direction makes every branch checkable by test rather than by inspection.
 *
 * Three shapes do not line up, and each one is refused rather than approximated:
 *
 * - `Match` on the wire is a oneof over `string`, `int64` and `bool`. A decimal has no variant, and
 *   Qdrant's REST schema has none either — `match` on a float never matched anything.
 * - `match.any` / `match.except` are `RepeatedStrings` or `RepeatedIntegers`, never a mixture. A list
 *   holding both has no wire form.
 * - A `FieldCondition` is a message of independent fields rather than a oneof, so it can carry several
 *   matchers at once. A [Condition.Field] holds one.
 *
 * The one value that is not carried across unchanged is `GeoRadius.radius`, a `float` here and a
 * `double` in the model. Metres through a `float` stay exact to well under a metre at any radius that
 * fits on Earth, so that narrowing is silent.
 */
internal object FilterMapping {

    fun toProto(filter: Filter): Common.Filter = Common.Filter.newBuilder().apply {
        filter.must?.let { addAllMust(it.map(::conditionToProto)) }
        filter.should?.let { addAllShould(it.map(::conditionToProto)) }
        filter.mustNot?.let { addAllMustNot(it.map(::conditionToProto)) }
        filter.minShould?.let {
            minShould = Common.MinShould.newBuilder()
                .addAllConditions(it.conditions.map(::conditionToProto))
                .setMinCount(it.minCount.toLong())
                .build()
        }
    }.build()

    fun toModel(filter: Common.Filter): Filter = Filter(
        must = filter.mustList.map(::conditionToModel).takeIf { it.isNotEmpty() },
        should = filter.shouldList.map(::conditionToModel).takeIf { it.isNotEmpty() },
        mustNot = filter.mustNotList.map(::conditionToModel).takeIf { it.isNotEmpty() },
        minShould = if (filter.hasMinShould()) {
            MinShould(
                conditions = filter.minShould.conditionsList.map(::conditionToModel),
                minCount = filter.minShould.minCount.toInt(),
            )
        } else {
            null
        },
    )

    private fun conditionToProto(condition: Condition): Common.Condition =
        Common.Condition.newBuilder().apply {
            when (condition) {
                is Condition.Field -> field = fieldConditionToProto(condition)
                is Condition.IsEmpty ->
                    isEmpty = Common.IsEmptyCondition.newBuilder().setKey(condition.key).build()
                is Condition.IsNull ->
                    isNull = Common.IsNullCondition.newBuilder().setKey(condition.key).build()
                is Condition.HasId -> hasId = Common.HasIdCondition.newBuilder()
                    .addAllHasId(condition.ids.map(PointMapping::idToProto))
                    .build()
                is Condition.HasVector ->
                    hasVector = Common.HasVectorCondition.newBuilder().setHasVector(condition.name).build()
                is Condition.Nested -> nested = Common.NestedCondition.newBuilder()
                    .setKey(condition.key)
                    .setFilter(toProto(condition.filter))
                    .build()
                is Condition.Sub -> filter = toProto(condition.filter)
            }
        }.build()

    private fun conditionToModel(condition: Common.Condition): Condition =
        when (condition.conditionOneOfCase) {
            Common.Condition.ConditionOneOfCase.FIELD -> fieldConditionToModel(condition.field)
            Common.Condition.ConditionOneOfCase.IS_EMPTY -> Condition.IsEmpty(condition.isEmpty.key)
            Common.Condition.ConditionOneOfCase.IS_NULL -> Condition.IsNull(condition.isNull.key)
            Common.Condition.ConditionOneOfCase.HAS_ID ->
                Condition.HasId(condition.hasId.hasIdList.map(PointMapping::idToModel))
            Common.Condition.ConditionOneOfCase.HAS_VECTOR ->
                Condition.HasVector(condition.hasVector.hasVector)
            Common.Condition.ConditionOneOfCase.NESTED ->
                Condition.Nested(condition.nested.key, toModel(condition.nested.filter))
            Common.Condition.ConditionOneOfCase.FILTER -> Condition.Sub(toModel(condition.filter))
            Common.Condition.ConditionOneOfCase.CONDITIONONEOF_NOT_SET, null ->
                throw IllegalArgumentException("a Condition with no variant set matches nothing and has no model form")
        }

    // One arm per matcher rather than a grouped `else` for the six that share the `match` field: the
    // `when` is exhaustive over the sealed type, so a matcher added later fails to compile here instead
    // of falling into whichever arm happens to be last.
    private fun fieldConditionToProto(condition: Condition.Field): Common.FieldCondition {
        val builder = Common.FieldCondition.newBuilder().setKey(condition.key)
        when (val matcher = condition.matcher) {
            is FieldMatcher.Match -> builder.match = matchValueToProto(matcher.value, condition.key)
            is FieldMatcher.MatchAny -> builder.match = anyToProto(matcher.values, condition.key, negated = false)
            is FieldMatcher.MatchExcept -> builder.match = anyToProto(matcher.values, condition.key, negated = true)
            is FieldMatcher.MatchText -> builder.match = Common.Match.newBuilder().setText(matcher.text).build()
            is FieldMatcher.MatchTextAny -> builder.match = Common.Match.newBuilder().setTextAny(matcher.text).build()
            is FieldMatcher.MatchPhrase -> builder.match = Common.Match.newBuilder().setPhrase(matcher.text).build()
            is FieldMatcher.Range -> builder.range = rangeToProto(matcher)
            is FieldMatcher.DatetimeRange -> builder.datetimeRange = datetimeRangeToProto(matcher, condition.key)
            is FieldMatcher.ValuesCount -> builder.valuesCount = valuesCountToProto(matcher)
            is FieldMatcher.GeoBoundingBox -> builder.geoBoundingBox = geoBoundingBoxToProto(matcher)
            is FieldMatcher.GeoRadius -> builder.geoRadius = geoRadiusToProto(matcher)
            is FieldMatcher.GeoPolygon -> builder.geoPolygon = geoPolygonToProto(matcher)
        }
        return builder.build()
    }

    /**
     * `FieldCondition` can set several matchers at once, which nothing here writes and nothing Qdrant
     * has been seen to send. It is reported rather than having one of them picked for it.
     */
    private fun fieldConditionToModel(condition: Common.FieldCondition): Condition.Field {
        val matchers = listOfNotNull(
            condition.takeIf { it.hasMatch() }?.let { matchToModel(it.match, it.key) },
            condition.takeIf { it.hasRange() }?.range?.let(::rangeToModel),
            condition.takeIf { it.hasDatetimeRange() }?.datetimeRange?.let(::datetimeRangeToModel),
            condition.takeIf { it.hasValuesCount() }?.valuesCount?.let(::valuesCountToModel),
            condition.takeIf { it.hasGeoBoundingBox() }?.geoBoundingBox?.let(::geoBoundingBoxToModel),
            condition.takeIf { it.hasGeoRadius() }?.geoRadius?.let(::geoRadiusToModel),
            condition.takeIf { it.hasGeoPolygon() }?.geoPolygon?.let(::geoPolygonToModel),
        )
        return Condition.Field(
            key = condition.key,
            matcher = matchers.singleOrNull() ?: throw IllegalArgumentException(
                if (matchers.isEmpty()) {
                    "the field condition on '${condition.key}' carries no matcher this client models"
                } else {
                    "the field condition on '${condition.key}' carries ${matchers.size} matchers at once, " +
                        "and a Condition.Field holds one"
                },
            ),
        )
    }

    private fun matchValueToProto(value: JsonPrimitive, key: String): Common.Match =
        Common.Match.newBuilder().apply {
            when {
                value.isString -> keyword = value.content
                value.booleanOrNull != null -> boolean = value.booleanOrNull!!
                value.longOrNull != null -> integer = value.longOrNull!!
                else -> throw IllegalArgumentException(
                    "match on '$key' is ${value.content}, and Qdrant matches a field against a string, an " +
                        "integer or a boolean only. Use a range for a decimal.",
                )
            }
        }.build()

    /**
     * `any` and `except` are one repeated type or the other. An empty list goes out as keywords: it
     * matches nothing either way, and picking a side beats refusing a filter Qdrant accepts.
     */
    private fun anyToProto(values: List<JsonPrimitive>, key: String, negated: Boolean): Common.Match {
        val clause = if (negated) "except" else "any"
        val allIntegers = values.isNotEmpty() && values.all { !it.isString && it.longOrNull != null }
        require(allIntegers || values.all { it.isString }) {
            "$clause on '$key' mixes strings and integers, and Qdrant's wire format carries one list of " +
                "strings or one of integers. Split it into two conditions."
        }
        return Common.Match.newBuilder().apply {
            if (allIntegers) {
                val list = Common.RepeatedIntegers.newBuilder().addAllIntegers(values.map { it.longOrNull!! }).build()
                if (negated) exceptIntegers = list else integers = list
            } else {
                val list = Common.RepeatedStrings.newBuilder().addAllStrings(values.map { it.content }).build()
                if (negated) exceptKeywords = list else keywords = list
            }
        }.build()
    }

    private fun matchToModel(match: Common.Match, key: String): FieldMatcher =
        when (match.matchValueCase) {
            Common.Match.MatchValueCase.KEYWORD -> FieldMatcher.Match(JsonPrimitive(match.keyword))
            Common.Match.MatchValueCase.INTEGER -> FieldMatcher.Match(JsonPrimitive(match.integer))
            Common.Match.MatchValueCase.BOOLEAN -> FieldMatcher.Match(JsonPrimitive(match.boolean))
            Common.Match.MatchValueCase.TEXT -> FieldMatcher.MatchText(match.text)
            Common.Match.MatchValueCase.TEXT_ANY -> FieldMatcher.MatchTextAny(match.textAny)
            Common.Match.MatchValueCase.PHRASE -> FieldMatcher.MatchPhrase(match.phrase)
            Common.Match.MatchValueCase.KEYWORDS ->
                FieldMatcher.MatchAny(match.keywords.stringsList.map(::JsonPrimitive))
            Common.Match.MatchValueCase.INTEGERS ->
                FieldMatcher.MatchAny(match.integers.integersList.map(::JsonPrimitive))
            Common.Match.MatchValueCase.EXCEPT_KEYWORDS ->
                FieldMatcher.MatchExcept(match.exceptKeywords.stringsList.map(::JsonPrimitive))
            Common.Match.MatchValueCase.EXCEPT_INTEGERS ->
                FieldMatcher.MatchExcept(match.exceptIntegers.integersList.map(::JsonPrimitive))
            Common.Match.MatchValueCase.MATCHVALUE_NOT_SET, null ->
                throw IllegalArgumentException("the match on '$key' has no value set")
        }

    private fun rangeToProto(matcher: FieldMatcher.Range): Common.Range = Common.Range.newBuilder().apply {
        matcher.gt?.let { gt = it }
        matcher.gte?.let { gte = it }
        matcher.lt?.let { lt = it }
        matcher.lte?.let { lte = it }
    }.build()

    private fun rangeToModel(range: Common.Range): FieldMatcher.Range = FieldMatcher.Range(
        gt = range.takeIf { it.hasGt() }?.gt,
        gte = range.takeIf { it.hasGte() }?.gte,
        lt = range.takeIf { it.hasLt() }?.lt,
        lte = range.takeIf { it.hasLte() }?.lte,
    )

    private fun datetimeRangeToProto(matcher: FieldMatcher.DatetimeRange, key: String): Common.DatetimeRange =
        Common.DatetimeRange.newBuilder().apply {
            matcher.gt?.let { gt = timestampOf(it, key) }
            matcher.gte?.let { gte = timestampOf(it, key) }
            matcher.lt?.let { lt = timestampOf(it, key) }
            matcher.lte?.let { lte = timestampOf(it, key) }
        }.build()

    private fun datetimeRangeToModel(range: Common.DatetimeRange): FieldMatcher.DatetimeRange =
        FieldMatcher.DatetimeRange(
            gt = range.takeIf { it.hasGt() }?.gt?.let(::rfc3339Of),
            gte = range.takeIf { it.hasGte() }?.gte?.let(::rfc3339Of),
            lt = range.takeIf { it.hasLt() }?.lt?.let(::rfc3339Of),
            lte = range.takeIf { it.hasLte() }?.lte?.let(::rfc3339Of),
        )

    private fun valuesCountToProto(matcher: FieldMatcher.ValuesCount): Common.ValuesCount =
        Common.ValuesCount.newBuilder().apply {
            matcher.gt?.let { gt = it.toLong() }
            matcher.gte?.let { gte = it.toLong() }
            matcher.lt?.let { lt = it.toLong() }
            matcher.lte?.let { lte = it.toLong() }
        }.build()

    private fun valuesCountToModel(count: Common.ValuesCount): FieldMatcher.ValuesCount =
        FieldMatcher.ValuesCount(
            gt = count.takeIf { it.hasGt() }?.gt?.toInt(),
            gte = count.takeIf { it.hasGte() }?.gte?.toInt(),
            lt = count.takeIf { it.hasLt() }?.lt?.toInt(),
            lte = count.takeIf { it.hasLte() }?.lte?.toInt(),
        )

    private fun geoBoundingBoxToProto(matcher: FieldMatcher.GeoBoundingBox): Common.GeoBoundingBox =
        Common.GeoBoundingBox.newBuilder()
            .setTopLeft(geoPointToProto(matcher.topLeft))
            .setBottomRight(geoPointToProto(matcher.bottomRight))
            .build()

    private fun geoRadiusToProto(matcher: FieldMatcher.GeoRadius): Common.GeoRadius =
        Common.GeoRadius.newBuilder()
            .setCenter(geoPointToProto(matcher.center))
            .setRadius(matcher.radius.toFloat())
            .build()

    private fun geoPolygonToProto(matcher: FieldMatcher.GeoPolygon): Common.GeoPolygon =
        Common.GeoPolygon.newBuilder()
            .setExterior(ringToProto(matcher.exterior))
            .addAllInteriors(matcher.interiors.map(::ringToProto))
            .build()

    private fun geoBoundingBoxToModel(box: Common.GeoBoundingBox): FieldMatcher.GeoBoundingBox =
        FieldMatcher.GeoBoundingBox(
            topLeft = geoPointToModel(box.topLeft),
            bottomRight = geoPointToModel(box.bottomRight),
        )

    private fun geoRadiusToModel(radius: Common.GeoRadius): FieldMatcher.GeoRadius = FieldMatcher.GeoRadius(
        center = geoPointToModel(radius.center),
        radius = radius.radius.toDouble(),
    )

    private fun geoPolygonToModel(polygon: Common.GeoPolygon): FieldMatcher.GeoPolygon = FieldMatcher.GeoPolygon(
        exterior = ringToModel(polygon.exterior),
        interiors = polygon.interiorsList.map(::ringToModel),
    )

    private fun geoPointToProto(point: GeoPoint): Common.GeoPoint =
        Common.GeoPoint.newBuilder().setLon(point.lon).setLat(point.lat).build()

    private fun geoPointToModel(point: Common.GeoPoint): GeoPoint = GeoPoint(lon = point.lon, lat = point.lat)

    private fun ringToProto(points: List<GeoPoint>): Common.GeoLineString =
        Common.GeoLineString.newBuilder().addAllPoints(points.map(::geoPointToProto)).build()

    private fun ringToModel(ring: Common.GeoLineString): List<GeoPoint> = ring.pointsList.map(::geoPointToModel)

    /**
     * The REST engine passes a datetime bound through as the string the caller wrote and lets Qdrant
     * parse it; a `Timestamp` has to be an instant, so the parsing happens here instead.
     *
     * Qdrant accepts a date, a naive datetime and an offset datetime, and reads the first two as UTC.
     * Same order, same assumption. A bound that comes back through [rfc3339Of] is normalized to UTC,
     * which is the instant Qdrant compared against whichever way it was written.
     */
    private fun timestampOf(value: String, key: String): Timestamp {
        val instant = parseInstant(value) ?: throw IllegalArgumentException(
            "the datetime bound '$value' on '$key' is not a date, a datetime or an RFC 3339 datetime with " +
                "an offset, and the gRPC wire carries an instant rather than the text.",
        )
        return Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()
    }

    private fun parseInstant(value: String): Instant? = sequenceOf(
        { OffsetDateTime.parse(value).toInstant() },
        { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) },
        { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC) },
    ).firstNotNullOfOrNull { parse ->
        try {
            parse()
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun rfc3339Of(timestamp: Timestamp): String =
        Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong()).toString()
}
