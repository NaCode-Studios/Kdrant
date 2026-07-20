package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Vector quantization, to shrink a collection's memory footprint. Build with [Scalar] or [Binary]. */
@Serializable(with = QuantizationConfigSerializer::class)
public sealed interface QuantizationConfig {

    /** Scalar (int8) quantization. */
    public data class Scalar(
        /** Quantile in `[0.5, 1.0]` used to clip outliers; `null` uses the full value range. */
        public val quantile: Float? = null,
        /** Keep quantized vectors in RAM regardless of the main storage config. */
        public val alwaysRam: Boolean? = null,
    ) : QuantizationConfig

    /** Binary quantization (1 bit per dimension) — the smallest footprint. */
    public data class Binary(
        public val alwaysRam: Boolean? = null,
    ) : QuantizationConfig
}

/** Write-only serializer emitting the `{"scalar":{…}}` / `{"binary":{…}}` shapes Qdrant expects. */
internal object QuantizationConfigSerializer : KSerializer<QuantizationConfig> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.QuantizationConfig")

    override fun serialize(encoder: Encoder, value: QuantizationConfig) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("QuantizationConfig can only be serialized to JSON")
        val element = when (value) {
            is QuantizationConfig.Scalar -> buildJsonObject {
                putJsonObject("scalar") {
                    put("type", "int8")
                    value.quantile?.let { put("quantile", it) }
                    value.alwaysRam?.let { put("always_ram", it) }
                }
            }
            is QuantizationConfig.Binary -> buildJsonObject {
                putJsonObject("binary") {
                    value.alwaysRam?.let { put("always_ram", it) }
                }
            }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): QuantizationConfig =
        throw SerializationException("QuantizationConfig is request-only and is never deserialized")
}
