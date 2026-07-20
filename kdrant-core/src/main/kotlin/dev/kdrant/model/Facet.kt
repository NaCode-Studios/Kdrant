package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A distinct value of a faceted payload key together with how many points carry it.
 * Returned by `facet`, ordered by descending [count].
 */
@Serializable
public data class FacetHit(
    @SerialName("value")
    public val value: FacetValue,
    @SerialName("count")
    public val count: Long,
)

/** A facet value: Qdrant returns a payload key's distinct values as a string, integer, or boolean. */
@Serializable(with = FacetValueSerializer::class)
public sealed interface FacetValue {

    /** A string value. */
    public data class StringValue(public val value: String) : FacetValue

    /** An integer value. */
    public data class IntValue(public val value: Long) : FacetValue

    /** A boolean value. */
    public data class BoolValue(public val value: Boolean) : FacetValue
}

/** Encodes/decodes a [FacetValue] as a bare JSON string, integer, or boolean primitive. */
internal object FacetValueSerializer : KSerializer<FacetValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.FacetValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FacetValue) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("FacetValue can only be serialized to JSON")
        val element = when (value) {
            is FacetValue.StringValue -> JsonPrimitive(value.value)
            is FacetValue.IntValue -> JsonPrimitive(value.value)
            is FacetValue.BoolValue -> JsonPrimitive(value.value)
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): FacetValue {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("FacetValue can only be deserialized from JSON")
        val primitive = json.decodeJsonElement().jsonPrimitive
        return when {
            primitive.isString -> FacetValue.StringValue(primitive.content)
            primitive.booleanOrNull != null -> FacetValue.BoolValue(primitive.booleanOrNull!!)
            primitive.longOrNull != null -> FacetValue.IntValue(primitive.longOrNull!!)
            else -> FacetValue.StringValue(primitive.content)
        }
    }
}
