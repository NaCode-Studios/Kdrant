package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The vector(s) attached to a point, in any shape Qdrant accepts or returns:
 *
 * - [Dense] — one anonymous dense vector: `[0.1, 0.2, ...]`.
 * - [Sparse] — a sparse vector (always used under a name): `{"indices":[...],"values":[...]}`.
 * - [MultiDense] — a multi-vector / late-interaction (ColBERT) vector: `[[...],[...]]`.
 * - [Named] — a map of vector-name to any of the above; dense, sparse and multi-vectors may be mixed.
 * - [Raw] — a shape this version does not model, kept verbatim so decoding a response never fails.
 */
@Serializable(with = VectorDataSerializer::class)
public sealed interface VectorData {

    /** One anonymous dense vector. */
    public data class Dense(public val values: List<Float>) : VectorData

    /**
     * One anonymous dense vector backed by a [FloatArray] — a zero-boxing fast path for the hot path
     * (values are never wrapped in `Float` objects, and serialization writes the array directly).
     * Prefer this over [Dense] for large vectors. Equality is by content.
     */
    public class DenseArray(public val values: FloatArray) : VectorData {
        override fun equals(other: Any?): Boolean =
            this === other || (other is DenseArray && values.contentEquals(other.values))

        override fun hashCode(): Int = values.contentHashCode()

        override fun toString(): String = "DenseArray(values=${values.contentToString()})"
    }

    /** A sparse vector; [indices] must be unique and the same length as [values]. Used under a name. */
    public data class Sparse(
        public val indices: List<Int>,
        public val values: List<Float>,
    ) : VectorData

    /** A multi-vector / late-interaction (ColBERT) vector: several equal-length dense vectors. */
    public data class MultiDense(public val vectors: List<List<Float>>) : VectorData

    /** Named vectors; each entry may be [Dense], [Sparse] or [MultiDense]. */
    public data class Named(public val vectors: Map<String, VectorData>) : VectorData

    /** A vector shape this version does not model, kept verbatim so response decoding degrades gracefully. */
    public data class Raw(public val json: JsonElement) : VectorData
}

internal object VectorDataSerializer : KSerializer<VectorData> {
    private val floatList = ListSerializer(Float.serializer())
    private val intList = ListSerializer(Int.serializer())
    private val floatArray = FloatArraySerializer()

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.VectorData")

    override fun serialize(encoder: Encoder, value: VectorData) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("VectorData can only be serialized to JSON")
        if (value is VectorData.DenseArray) {
            // Zero-boxing fast path: write the FloatArray straight to a JSON number array.
            encoder.encodeSerializableValue(floatArray, value.values)
        } else {
            json.encodeJsonElement(toJson(value))
        }
    }

    private fun toJson(value: VectorData): JsonElement = when (value) {
        is VectorData.Dense -> JsonArray(value.values.map { JsonPrimitive(it) })
        is VectorData.DenseArray -> JsonArray(value.values.map { JsonPrimitive(it) })
        is VectorData.Sparse -> buildJsonObject {
            put("indices", JsonArray(value.indices.map { JsonPrimitive(it) }))
            put("values", JsonArray(value.values.map { JsonPrimitive(it) }))
        }
        is VectorData.MultiDense ->
            JsonArray(value.vectors.map { row -> JsonArray(row.map { JsonPrimitive(it) }) })
        is VectorData.Named -> buildJsonObject { value.vectors.forEach { (name, data) -> put(name, toJson(data)) } }
        is VectorData.Raw -> value.json
    }

    override fun deserialize(decoder: Decoder): VectorData {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("VectorData can only be deserialized from JSON")
        return fromJson(json.json, json.decodeJsonElement())
    }

    private fun fromJson(json: Json, element: JsonElement): VectorData = try {
        when {
            element is JsonArray && element.isNotEmpty() && element.all { it is JsonArray } ->
                VectorData.MultiDense(element.map { json.decodeFromJsonElement(floatList, it) })

            element is JsonArray ->
                VectorData.Dense(json.decodeFromJsonElement(floatList, element))

            element is JsonObject && element.size == 2 && "indices" in element && "values" in element ->
                VectorData.Sparse(
                    indices = json.decodeFromJsonElement(intList, element.getValue("indices")),
                    values = json.decodeFromJsonElement(floatList, element.getValue("values")),
                )

            element is JsonObject ->
                VectorData.Named(element.mapValues { (_, v) -> fromJson(json, v) })

            else -> VectorData.Raw(element)
        }
    } catch (e: Exception) {
        // Never fail response decoding on an unexpected vector shape — keep it verbatim.
        VectorData.Raw(element)
    }
}
