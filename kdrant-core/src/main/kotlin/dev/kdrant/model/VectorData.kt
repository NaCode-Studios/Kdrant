package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/**
 * The vector(s) attached to a point, which Qdrant represents in two shapes:
 *
 * - [Dense] — one anonymous vector: a JSON array `[0.1, 0.2, ...]`.
 * - [Named] — a map of vector-name to values: `{"text": [...], "image": [...]}`.
 *
 * (Sparse vectors are not yet supported, but this type can grow to hold them.)
 */
@Serializable(with = VectorDataSerializer::class)
public sealed interface VectorData {

    public data class Dense(public val values: List<Float>) : VectorData

    public data class Named(public val vectors: Map<String, List<Float>>) : VectorData
}

internal object VectorDataSerializer : KSerializer<VectorData> {
    private val floatList = ListSerializer(Float.serializer())
    private val namedMap = MapSerializer(String.serializer(), floatList)

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.VectorData")

    override fun serialize(encoder: Encoder, value: VectorData) {
        when (value) {
            is VectorData.Dense -> encoder.encodeSerializableValue(floatList, value.values)
            is VectorData.Named -> encoder.encodeSerializableValue(namedMap, value.vectors)
        }
    }

    override fun deserialize(decoder: Decoder): VectorData {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("VectorData can only be deserialized from JSON")
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> VectorData.Dense(json.json.decodeFromJsonElement(floatList, element))
            is JsonObject -> VectorData.Named(json.json.decodeFromJsonElement(namedMap, element))
            else -> throw SerializationException("Unexpected vector JSON (expected array or object): $element")
        }
    }
}
