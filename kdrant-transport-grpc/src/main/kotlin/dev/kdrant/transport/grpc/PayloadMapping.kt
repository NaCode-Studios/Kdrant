package dev.kdrant.transport.grpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import qdrant.JsonWithInt

/**
 * Payload conversion between Kdrant's `kotlinx.serialization` JSON and Qdrant's protobuf payload.
 *
 * The two are not the same shape, and the difference is the whole reason this file exists. JSON has one
 * number type; Qdrant's protobuf payload forked `google.protobuf.Value` specifically to carry
 * `integer_value` separately from `double_value`, because a vector database that rounds an id to a
 * double loses it. So an integral JSON number becomes an integer on the wire and comes back integral,
 * rather than making a round trip through `Double` and arriving as `42.0`.
 */
internal object PayloadMapping {

    /** A JSON payload object as Qdrant's protobuf field map. */
    fun toProto(payload: JsonObject): Map<String, JsonWithInt.Value> =
        payload.mapValues { (_, element) -> valueToProto(element) }

    /** Qdrant's protobuf field map back as a JSON payload object. */
    fun toJson(fields: Map<String, JsonWithInt.Value>): JsonObject =
        buildJsonObject { fields.forEach { (key, value) -> put(key, valueToJson(value)) } }

    fun valueToProto(element: JsonElement): JsonWithInt.Value {
        val builder = JsonWithInt.Value.newBuilder()
        when (element) {
            is JsonNull -> builder.nullValue = JsonWithInt.NullValue.NULL_VALUE
            is JsonPrimitive -> primitiveToProto(builder, element)
            is JsonObject ->
                builder.structValue =
                    JsonWithInt.Struct.newBuilder().putAllFields(toProto(element)).build()
            is JsonArray ->
                builder.listValue =
                    JsonWithInt.ListValue.newBuilder().addAllValues(element.map { valueToProto(it) }).build()
        }
        return builder.build()
    }

    private fun primitiveToProto(builder: JsonWithInt.Value.Builder, primitive: JsonPrimitive) {
        when {
            primitive.isString -> builder.stringValue = primitive.content
            primitive.booleanOrNull != null -> builder.boolValue = primitive.booleanOrNull!!
            else -> numberToProto(builder, primitive.content)
        }
    }

    /**
     * Integral first, and by the text rather than by what it parses to. `longOrNull` is not enough on
     * its own: a number too large for `Long` still parses as a `Double`, so testing that first would
     * round a 30-digit id to the nearest double and carry on as if nothing happened.
     *
     * A value that fits neither is rejected rather than rounded or turned into a string. Both of those
     * make the write succeed and the data mean something else, which is found much later and far from
     * here. The REST engine has no such limit, because JSON has no int64 ceiling.
     */
    private fun numberToProto(builder: JsonWithInt.Value.Builder, content: String) {
        val integral = content.none { it == '.' || it == 'e' || it == 'E' }
        if (integral) {
            builder.integerValue = content.toLongOrNull() ?: throw IllegalArgumentException(
                "the payload value $content does not fit in the int64 the gRPC payload wire uses. " +
                    "Store it as a string, or use the REST engine, whose JSON has no such limit.",
            )
        } else {
            val double = content.toDoubleOrNull()
            require(double != null && double.isFinite()) {
                "the payload value $content is not a number the gRPC payload wire can carry"
            }
            builder.doubleValue = double
        }
    }

    fun valueToJson(value: JsonWithInt.Value): JsonElement = when (value.kindCase) {
        JsonWithInt.Value.KindCase.NULL_VALUE -> JsonNull
        JsonWithInt.Value.KindCase.DOUBLE_VALUE -> JsonPrimitive(value.doubleValue)
        JsonWithInt.Value.KindCase.INTEGER_VALUE -> JsonPrimitive(value.integerValue)
        JsonWithInt.Value.KindCase.STRING_VALUE -> JsonPrimitive(value.stringValue)
        JsonWithInt.Value.KindCase.BOOL_VALUE -> JsonPrimitive(value.boolValue)
        JsonWithInt.Value.KindCase.STRUCT_VALUE -> toJson(value.structValue.fieldsMap)
        JsonWithInt.Value.KindCase.LIST_VALUE -> JsonArray(value.listValue.valuesList.map { valueToJson(it) })
        // A Value with no variant set is what the proto comment calls an error. It is a payload field
        // Qdrant sent and this client cannot read, so it decodes to null rather than failing the
        // whole response over one field.
        JsonWithInt.Value.KindCase.KIND_NOT_SET, null -> JsonNull
    }
}
