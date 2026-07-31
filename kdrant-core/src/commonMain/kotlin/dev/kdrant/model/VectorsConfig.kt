package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonObject

/**
 * The `vectors` config of a collection, which Qdrant represents in two shapes:
 *
 * - [Single] — one anonymous vector: the [VectorParams] object appears directly under `vectors`.
 * - [Named] — a map of vector-name to [VectorParams].
 *
 * Both shapes are unified behind this sealed type; the serializer emits the correct JSON form.
 */
@Serializable(with = VectorsConfigSerializer::class)
public sealed interface VectorsConfig {

    public data class Single(public val params: VectorParams) : VectorsConfig

    public data class Named(public val vectors: Map<String, VectorParams>) : VectorsConfig

    public companion object {
        public fun single(size: Long, distance: Distance): VectorsConfig =
            Single(VectorParams(size, distance))

        public fun named(vectors: Map<String, VectorParams>): VectorsConfig =
            Named(vectors)
    }
}

internal object VectorsConfigSerializer : KSerializer<VectorsConfig> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.VectorsConfig")

    override fun serialize(encoder: Encoder, value: VectorsConfig) {
        when (value) {
            is VectorsConfig.Single ->
                encoder.encodeSerializableValue(VectorParams.serializer(), value.params)

            is VectorsConfig.Named ->
                encoder.encodeSerializableValue(
                    MapSerializer(String.serializer(), VectorParams.serializer()),
                    value.vectors,
                )
        }
    }

    override fun deserialize(decoder: Decoder): VectorsConfig {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("VectorsConfig can only be deserialized from JSON")
        val obj = json.decodeJsonElement().jsonObject
        // A single anonymous vector always carries `size` + `distance` at the top level;
        // otherwise it is a map of name -> VectorParams.
        return if ("size" in obj && "distance" in obj) {
            VectorsConfig.Single(json.json.decodeFromJsonElement(VectorParams.serializer(), obj))
        } else {
            VectorsConfig.Named(
                obj.mapValues { (_, element) ->
                    json.json.decodeFromJsonElement(VectorParams.serializer(), element)
                },
            )
        }
    }
}
