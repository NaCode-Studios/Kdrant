package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Vector quantization, to shrink a collection's memory footprint.
 *
 * The four families trade recall against size differently, and the choice is not a dial:
 *
 * - [Scalar] stores one byte per dimension. The safe default, and the one to start from.
 * - [Product] stores a codebook index per sub-vector and is the answer where scalar loses too much
 *   recall, at the cost of a slower comparison.
 * - [Binary] stores one bit per dimension. The smallest, and it needs rescoring to be usable on most
 *   embeddings.
 * - [Turbo] is Qdrant's TurboQuant at one of four bit sizes, and it is what Qdrant's own benchmarks and
 *   release notes are written about. At four bits it can also be the collection's primary storage rather
 *   than a layer over it, which is [VectorDatatype.TURBO4] instead of this.
 *
 * Quantizing without asking for rescoring on the read side is the mistake worth naming here: see
 * [QuantizationSearchParams].
 */
@Serializable(with = QuantizationConfigSerializer::class)
public sealed interface QuantizationConfig {

    /** Scalar (int8) quantization. */
    public data class Scalar(
        /** Quantile in `[0.5, 1.0]` used to clip outliers; `null` uses the full value range. */
        public val quantile: Float? = null,
        /** Keep quantized vectors in RAM regardless of the main storage config. */
        public val alwaysRam: Boolean? = null,
        /** Memory placement of the quantized vectors. Overrides [alwaysRam] when both are set. */
        public val memory: Memory? = null,
    ) : QuantizationConfig

    /** Binary quantization (1 bit per dimension) — the smallest footprint. */
    public data class Binary(
        public val alwaysRam: Boolean? = null,
        /** Memory placement of the quantized vectors. Overrides [alwaysRam] when both are set. */
        public val memory: Memory? = null,
    ) : QuantizationConfig

    /**
     * Product quantization at a fixed compression ratio.
     *
     * It keeps more recall than scalar quantization at the same size and costs more per comparison,
     * because scoring a product-quantized vector is a table lookup per sub-vector rather than arithmetic.
     * Reach for it when scalar has lost too much and binary is out of the question.
     */
    public data class Product(
        public val compression: CompressionRatio,
        public val alwaysRam: Boolean? = null,
        /** Memory placement of the quantized vectors. Overrides [alwaysRam] when both are set. */
        public val memory: Memory? = null,
    ) : QuantizationConfig

    /**
     * TurboQuant, at one of four bit sizes.
     *
     * `null` bits leaves the server's default. This is quantization *over* full-precision storage; to
     * store nothing but 4-bit vectors and keep no originals, set [VectorDatatype.TURBO4] on the vector
     * instead, which is a different decision about what the collection holds.
     */
    public data class Turbo(
        public val bits: TurboQuantBitSize? = null,
        public val alwaysRam: Boolean? = null,
        /** Memory placement of the quantized vectors. Overrides [alwaysRam] when both are set. */
        public val memory: Memory? = null,
    ) : QuantizationConfig
}

/** How much smaller product quantization makes a vector. */
@Serializable
public enum class CompressionRatio {
    @SerialName("x4")
    X4,

    @SerialName("x8")
    X8,

    @SerialName("x16")
    X16,

    @SerialName("x32")
    X32,

    @SerialName("x64")
    X64,
}

/** Bits per dimension TurboQuant keeps. Fewer is smaller and less accurate. */
@Serializable
public enum class TurboQuantBitSize {
    @SerialName("bits1")
    BITS_1,

    @SerialName("bits1_5")
    BITS_1_5,

    @SerialName("bits2")
    BITS_2,

    @SerialName("bits4")
    BITS_4,
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
                    value.memory?.let { put("memory", json.json.encodeToJsonElement(Memory.serializer(), it)) }
                }
            }
            is QuantizationConfig.Binary -> buildJsonObject {
                putJsonObject("binary") {
                    value.alwaysRam?.let { put("always_ram", it) }
                    value.memory?.let { put("memory", json.json.encodeToJsonElement(Memory.serializer(), it)) }
                }
            }
            is QuantizationConfig.Product -> buildJsonObject {
                putJsonObject("product") {
                    put("compression", json.json.encodeToJsonElement(CompressionRatio.serializer(), value.compression))
                    value.alwaysRam?.let { put("always_ram", it) }
                    value.memory?.let { put("memory", json.json.encodeToJsonElement(Memory.serializer(), it)) }
                }
            }
            is QuantizationConfig.Turbo -> buildJsonObject {
                putJsonObject("turbo") {
                    value.bits?.let {
                        put("bits", json.json.encodeToJsonElement(TurboQuantBitSize.serializer(), it))
                    }
                    value.alwaysRam?.let { put("always_ram", it) }
                    value.memory?.let { put("memory", json.json.encodeToJsonElement(Memory.serializer(), it)) }
                }
            }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): QuantizationConfig =
        throw SerializationException("QuantizationConfig is request-only and is never deserialized")
}
