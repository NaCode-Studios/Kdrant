package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Payload projection for search/scroll results. Qdrant accepts four wire shapes:
 *
 * - `true`  -> [All]
 * - `false` -> [None]
 * - `["a","b"]` (shorthand) or `{"include":["a","b"]}` -> [Include]
 * - `{"exclude":["a","b"]}` -> [Exclude]
 */
@Serializable(with = WithPayloadSerializer::class)
public sealed interface WithPayload {

    /** Return the whole payload. */
    public data object All : WithPayload

    /** Return no payload. */
    public data object None : WithPayload

    /** Return only the listed keys. */
    public data class Include(public val fields: List<String>) : WithPayload

    /** Return the whole payload except the listed keys. */
    public data class Exclude(public val fields: List<String>) : WithPayload

    public companion object {
        public fun include(vararg fields: String): WithPayload = Include(fields.toList())
        public fun exclude(vararg fields: String): WithPayload = Exclude(fields.toList())
    }
}

internal object WithPayloadSerializer : KSerializer<WithPayload> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.WithPayload")

    override fun serialize(encoder: Encoder, value: WithPayload) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("WithPayload can only be serialized to JSON")
        val element: JsonElement = when (value) {
            WithPayload.All -> JsonPrimitive(true)
            WithPayload.None -> JsonPrimitive(false)
            is WithPayload.Include -> buildJsonObject {
                put("include", JsonArray(value.fields.map { JsonPrimitive(it) }))
            }
            is WithPayload.Exclude -> buildJsonObject {
                put("exclude", JsonArray(value.fields.map { JsonPrimitive(it) }))
            }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): WithPayload {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("WithPayload can only be deserialized from JSON")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> when (element.booleanOrNull) {
                true -> WithPayload.All
                false -> WithPayload.None
                null -> throw SerializationException("Invalid with_payload primitive: $element")
            }
            is JsonArray -> WithPayload.Include(element.map { it.jsonPrimitive.content })
            is JsonObject -> {
                val include = element["include"]?.jsonArray?.map { it.jsonPrimitive.content }
                val exclude = element["exclude"]?.jsonArray?.map { it.jsonPrimitive.content }
                when {
                    include != null -> WithPayload.Include(include)
                    exclude != null -> WithPayload.Exclude(exclude)
                    else -> throw SerializationException("Invalid with_payload object: $element")
                }
            }
        }
    }
}
