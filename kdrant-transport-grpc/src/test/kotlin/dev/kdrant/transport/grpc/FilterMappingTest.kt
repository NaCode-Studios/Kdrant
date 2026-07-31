package dev.kdrant.transport.grpc

import dev.kdrant.model.Condition
import dev.kdrant.model.FieldMatcher
import dev.kdrant.model.Filter
import dev.kdrant.model.GeoPoint
import dev.kdrant.model.MinShould
import dev.kdrant.model.PointId
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import qdrant.Common

/**
 * The mapping has no round trip in production — Qdrant never returns a filter — so these tests are
 * where the reverse direction earns its keep: every condition type and every matcher goes out to
 * protobuf and comes back, and a field wired to the wrong place fails to come back the same.
 *
 * The three shapes that cannot survive the wire are asserted as failures rather than left to be found
 * against a running server.
 */
class FilterMappingTest {

    /** Every matcher a [Condition.Field] can carry, one entry per branch of the mapping. */
    private val matchers: Map<String, FieldMatcher> = mapOf(
        "match keyword" to FieldMatcher.Match(JsonPrimitive("kotlin")),
        "match integer" to FieldMatcher.Match(JsonPrimitive(2024)),
        "match boolean" to FieldMatcher.Match(JsonPrimitive(true)),
        "match any keywords" to FieldMatcher.MatchAny(listOf(JsonPrimitive("it"), JsonPrimitive("en"))),
        "match any integers" to FieldMatcher.MatchAny(listOf(JsonPrimitive(1), JsonPrimitive(2))),
        "match except keywords" to FieldMatcher.MatchExcept(listOf(JsonPrimitive("draft"))),
        "match except integers" to FieldMatcher.MatchExcept(listOf(JsonPrimitive(7))),
        "match text" to FieldMatcher.MatchText("vector database"),
        "match text any" to FieldMatcher.MatchTextAny("vector database"),
        "match phrase" to FieldMatcher.MatchPhrase("vector database"),
        "range" to FieldMatcher.Range(gt = 1.0, lte = 10.5),
        "range with every bound" to FieldMatcher.Range(gt = 1.0, gte = 2.0, lt = 9.0, lte = 10.0),
        "datetime range" to FieldMatcher.DatetimeRange(gte = "2024-01-01T00:00:00Z", lt = "2025-01-01T00:00:00Z"),
        "values count" to FieldMatcher.ValuesCount(gte = 2, lte = 5),
        "geo bounding box" to FieldMatcher.GeoBoundingBox(
            topLeft = GeoPoint(lon = 9.18, lat = 45.46),
            bottomRight = GeoPoint(lon = 12.49, lat = 41.90),
        ),
        // Metres, chosen to be exact in the float the wire uses; see the mapping's KDoc.
        "geo radius" to FieldMatcher.GeoRadius(center = GeoPoint(lon = 9.18, lat = 45.46), radius = 1500.0),
        "geo polygon" to FieldMatcher.GeoPolygon(
            exterior = squareRing(0.0),
            interiors = listOf(squareRing(0.25)),
        ),
    )

    private val conditions: Map<String, Condition> = mapOf(
        "is empty" to Condition.IsEmpty("tags"),
        "is null" to Condition.IsNull("author"),
        "has id" to Condition.HasId(listOf(PointId.num(1), PointId.uuid("550e8400-e29b-41d4-a716-446655440000"))),
        "has vector" to Condition.HasVector("image"),
        "has anonymous vector" to Condition.HasVector(""),
        "nested" to Condition.Nested("chapters", Filter(must = listOf(Condition.IsEmpty("notes")))),
        "sub-filter" to Condition.Sub(Filter(mustNot = listOf(Condition.IsNull("author")))),
    ) + matchers.mapKeys { (name, _) -> "field / $name" }
        .mapValues { (_, matcher) -> Condition.Field("field", matcher) }

    @TestFactory
    fun `every condition and every matcher survives the round trip`(): List<DynamicTest> =
        conditions.map { (name, condition) ->
            DynamicTest.dynamicTest(name) {
                val filter = Filter(must = listOf(condition))

                assertEquals(filter, FilterMapping.toModel(FilterMapping.toProto(filter)))
            }
        }

    @Test
    fun `the four clauses land on the four wire fields, not on whichever one comes first`() {
        val filter = Filter(
            must = listOf(Condition.IsEmpty("a")),
            should = listOf(Condition.IsNull("b")),
            mustNot = listOf(Condition.HasVector("c")),
            minShould = MinShould(conditions = listOf(Condition.IsEmpty("d"), Condition.IsEmpty("e")), minCount = 1),
        )

        val proto = FilterMapping.toProto(filter)

        assertEquals("a", proto.mustList.single().isEmpty.key)
        assertEquals("b", proto.shouldList.single().isNull.key)
        assertEquals("c", proto.mustNotList.single().hasVector.hasVector)
        assertEquals(1L, proto.minShould.minCount)
        assertEquals(listOf("d", "e"), proto.minShould.conditionsList.map { it.isEmpty.key })
        assertEquals(filter, FilterMapping.toModel(proto))
    }

    @Test
    fun `an absent clause stays absent rather than becoming an empty one`() {
        // An empty `must` is not the same request as no `must`, and a filter that grew one on the way
        // through would be a different query from the one the caller built.
        val proto = FilterMapping.toProto(Filter(must = listOf(Condition.IsEmpty("a"))))

        assertTrue(proto.shouldList.isEmpty())
        assertTrue(proto.mustNotList.isEmpty())
        assertTrue(!proto.hasMinShould())

        val model = FilterMapping.toModel(proto)
        assertNull(model.should)
        assertNull(model.mustNot)
        assertNull(model.minShould)
    }

    @Test
    fun `nesting recurses on both sides`() {
        // Three levels, alternating the two recursive shapes: a sub-filter holding a nested condition
        // holding a sub-filter. Mapping one level and stopping would still pass a single-level test.
        val filter = Filter(
            must = listOf(
                Condition.Sub(
                    Filter(
                        should = listOf(
                            Condition.Nested(
                                key = "chapters",
                                filter = Filter(
                                    must = listOf(
                                        Condition.Sub(
                                            Filter(mustNot = listOf(Condition.Field("lang", matchers.getValue("match keyword")))),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(filter, FilterMapping.toModel(FilterMapping.toProto(filter)))
    }

    @Test
    fun `a decimal match is refused, because the wire has no variant for it and neither does Qdrant`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FilterMapping.toProto(Filter(must = listOf(Condition.Field("score", FieldMatcher.Match(JsonPrimitive(1.5))))))
        }

        assertTrue(error.message!!.contains("range for a decimal"), error.message)
    }

    @Test
    fun `an any list mixing strings and integers is refused rather than stringified`() {
        val mixed = FieldMatcher.MatchAny(listOf(JsonPrimitive("it"), JsonPrimitive(2)))

        val error = assertThrows(IllegalArgumentException::class.java) {
            FilterMapping.toProto(Filter(must = listOf(Condition.Field("lang", mixed))))
        }

        assertTrue(error.message!!.contains("mixes strings and integers"), error.message)
    }

    @Test
    fun `an empty any list goes out as keywords, which matches nothing either way`() {
        val proto = FilterMapping.toProto(Filter(must = listOf(Condition.Field("lang", FieldMatcher.MatchAny(emptyList())))))

        assertEquals(Common.Match.MatchValueCase.KEYWORDS, proto.mustList.single().field.match.matchValueCase)
    }

    @Test
    fun `a datetime bound written with an offset arrives as the instant it names`() {
        // 08:00+02:00 and 06:00Z are the same moment. The wire carries an instant, so the offset form
        // is normalized rather than kept, and the round trip is by instant and not by text.
        val offset = Filter(
            must = listOf(Condition.Field("at", FieldMatcher.DatetimeRange(gte = "2024-06-01T08:00:00+02:00"))),
        )

        val model = FilterMapping.toModel(FilterMapping.toProto(offset))

        val matcher = (model.must!!.single() as Condition.Field).matcher as FieldMatcher.DatetimeRange
        assertEquals("2024-06-01T06:00:00Z", matcher.gte)
    }

    @Test
    fun `a bare date is read as midnight UTC, the way Qdrant reads it`() {
        val filter = Filter(must = listOf(Condition.Field("at", FieldMatcher.DatetimeRange(lt = "2024-06-01"))))

        val model = FilterMapping.toModel(FilterMapping.toProto(filter))

        val matcher = (model.must!!.single() as Condition.Field).matcher as FieldMatcher.DatetimeRange
        assertEquals("2024-06-01T00:00:00Z", matcher.lt)
    }

    @Test
    fun `a datetime bound that is not a datetime is refused where it was written`() {
        val filter = Filter(must = listOf(Condition.Field("at", FieldMatcher.DatetimeRange(gte = "last tuesday"))))

        val error = assertThrows(IllegalArgumentException::class.java) { FilterMapping.toProto(filter) }

        assertTrue(error.message!!.contains("last tuesday"), error.message)
    }

    @Test
    fun `a field condition carrying two matchers at once has no model form`() {
        // Qdrant's FieldCondition is a message of independent fields rather than a oneof, so this is
        // representable on the wire even though nothing this client writes produces it.
        val condition = Common.Condition.newBuilder()
            .setField(
                Common.FieldCondition.newBuilder()
                    .setKey("k")
                    .setMatch(Common.Match.newBuilder().setKeyword("v"))
                    .setRange(Common.Range.newBuilder().setGt(1.0)),
            )
            .build()

        val error = assertThrows(IllegalArgumentException::class.java) {
            FilterMapping.toModel(Common.Filter.newBuilder().addMust(condition).build())
        }

        assertTrue(error.message!!.contains("2 matchers at once"), error.message)
    }

    @Test
    fun `a condition with no variant set is reported rather than dropped`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FilterMapping.toModel(Common.Filter.newBuilder().addMust(Common.Condition.getDefaultInstance()).build())
        }

        assertTrue(error.message!!.contains("no variant set"), error.message)
    }

    private companion object {
        /** A closed ring, as Qdrant requires: four corners with the first repeated last. */
        fun squareRing(inset: Double): List<GeoPoint> = listOf(
            GeoPoint(lon = 0.0 + inset, lat = 0.0 + inset),
            GeoPoint(lon = 1.0 - inset, lat = 0.0 + inset),
            GeoPoint(lon = 1.0 - inset, lat = 1.0 - inset),
            GeoPoint(lon = 0.0 + inset, lat = 0.0 + inset),
        )
    }
}
