package dev.kdrant.transport.grpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import qdrant.JsonWithInt

class PayloadMappingTest {

    private fun roundTrip(json: String): JsonObject =
        PayloadMapping.toJson(PayloadMapping.toProto(Json.parseToJsonElement(json) as JsonObject))

    @Test
    fun `an integer survives the round trip as an integer, not as a double`() {
        val proto = PayloadMapping.toProto(buildJsonObject { put("id", JsonPrimitive(42)) })

        assertEquals(JsonWithInt.Value.KindCase.INTEGER_VALUE, proto.getValue("id").kindCase)
        assertEquals(42L, proto.getValue("id").integerValue)
        assertEquals("42", PayloadMapping.toJson(proto)["id"].toString())
    }

    @Test
    fun `a fractional number stays a double`() {
        val proto = PayloadMapping.toProto(buildJsonObject { put("score", JsonPrimitive(0.5)) })

        assertEquals(JsonWithInt.Value.KindCase.DOUBLE_VALUE, proto.getValue("score").kindCase)
        assertEquals(0.5, proto.getValue("score").doubleValue)
    }

    @Test
    fun `an id beyond Long is rejected rather than rounded to the nearest double`() {
        val huge = "123456789012345678901234567890"

        val error = assertThrows(IllegalArgumentException::class.java) {
            PayloadMapping.toProto(buildJsonObject { put("big", JsonPrimitive(huge.toBigInteger())) })
        }

        assertEquals(true, error.message!!.contains("int64"), error.message)
        // It parses as a Double perfectly happily, which is exactly why testing longOrNull first is
        // not enough on its own.
        assertEquals(true, huge.toDoubleOrNull()!!.isFinite())
    }

    @Test
    fun `the largest value the wire can carry still goes through`() {
        val proto = PayloadMapping.toProto(buildJsonObject { put("id", JsonPrimitive(Long.MAX_VALUE)) })

        assertEquals(JsonWithInt.Value.KindCase.INTEGER_VALUE, proto.getValue("id").kindCase)
        assertEquals(Long.MAX_VALUE, proto.getValue("id").integerValue)
    }

    @Test
    fun `every JSON shape round-trips`() {
        val json = """
            {"title":"Intro","lang":"en","year":2026,"score":0.87,"draft":false,"reviewer":null,
             "tags":["a","b"],"counts":[1,2,3],
             "author":{"name":"ada","rating":4.5,"badges":["x"]}}
        """.trimIndent()

        assertEquals(Json.parseToJsonElement(json), roundTrip(json))
    }

    @Test
    fun `nesting round-trips at depth`() {
        val json = """{"a":{"b":{"c":{"d":[{"e":1},{"e":2}]}}}}"""

        assertEquals(Json.parseToJsonElement(json), roundTrip(json))
    }

    @Test
    fun `an explicit null is a null value, not a missing field`() {
        val proto = PayloadMapping.toProto(buildJsonObject { put("reviewer", JsonNull) })

        assertEquals(JsonWithInt.Value.KindCase.NULL_VALUE, proto.getValue("reviewer").kindCase)
        assertEquals(JsonNull, PayloadMapping.toJson(proto)["reviewer"])
    }

    @Test
    fun `a value Qdrant sent with no variant set decodes to null instead of failing the response`() {
        val unset = JsonWithInt.Value.newBuilder().build()

        assertEquals(JsonNull, PayloadMapping.valueToJson(unset))
        assertEquals(
            buildJsonObject { put("odd", JsonNull) },
            PayloadMapping.toJson(mapOf("odd" to unset)),
        )
    }

    @Test
    fun `a string that looks like a number stays a string`() {
        val proto = PayloadMapping.toProto(buildJsonObject { put("zip", JsonPrimitive("00185")) })

        assertEquals(JsonWithInt.Value.KindCase.STRING_VALUE, proto.getValue("zip").kindCase)
        assertEquals("00185", PayloadMapping.toJson(proto)["zip"]!!.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `an empty object and an empty array survive as themselves`() {
        assertEquals(Json.parseToJsonElement("""{"o":{},"a":[]}"""), roundTrip("""{"o":{},"a":[]}"""))
    }
}
