package dev.kdrant.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Qdrant point identifier. Qdrant accepts exactly two forms: an unsigned 64-bit integer
 * or a UUID string. Modelled as a sealed type so the two cases are impossible to confuse.
 */
@Serializable(with = PointIdSerializer::class)
public sealed interface PointId {

    /** Unsigned 64-bit integer id (may exceed [Long.MAX_VALUE]). */
    public data class Num(public val value: ULong) : PointId

    /** UUID id, kept as its string form. */
    public data class Uuid(public val value: String) : PointId

    public companion object {
        public fun num(value: ULong): PointId = Num(value)

        public fun num(value: Long): PointId {
            require(value >= 0) { "point id must be non-negative, was $value" }
            return Num(value.toULong())
        }

        public fun uuid(value: String): PointId = Uuid(value)
    }
}

/**
 * Serializes a [PointId] as a raw JSON number (for [PointId.Num], preserving the full uint64
 * range via an unquoted literal) or a JSON string (for [PointId.Uuid]).
 */
internal object PointIdSerializer : KSerializer<PointId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kdrant.model.PointId", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: PointId) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("PointId can only be serialized to JSON")
        val element = when (value) {
            // Unquoted literal so ids above Long.MAX_VALUE round-trip losslessly.
            is PointId.Num -> JsonUnquotedLiteral(value.value.toString())
            is PointId.Uuid -> JsonPrimitive(value.value)
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): PointId {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("PointId can only be deserialized from JSON")
        val primitive = json.decodeJsonElement().jsonPrimitive
        return if (primitive.isString) {
            PointId.Uuid(primitive.content)
        } else {
            PointId.Num(primitive.content.toULong())
        }
    }
}
