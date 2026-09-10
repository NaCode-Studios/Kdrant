package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How a full-text index reduces tokens to a stem.
 *
 * It is not a tuning knob for every corpus. Stemming makes "running" match "run", which is what somebody
 * searching prose wants and is wrong for a field of product codes or identifiers, and for a language whose
 * stemmer is aggressive it loses distinctions the reader was relying on. Qdrant's default is no stemming,
 * so this is a decision to opt into rather than one to undo.
 *
 * [Disabled] exists because Qdrant 1.19 added it: before that the way to turn stemming off was a magic
 * `"none"` language, which it now deprecates.
 */
@Serializable(with = StemmingAlgorithmSerializer::class)
public sealed interface StemmingAlgorithm {

    /** The Snowball stemmer for one language. */
    public data class Snowball(public val language: SnowballLanguage) : StemmingAlgorithm

    /** No stemming, said explicitly, which overrides whatever the field's language would default to. */
    public data object Disabled : StemmingAlgorithm
}

/** The languages Qdrant's Snowball stemmer supports. */
@Serializable
public enum class SnowballLanguage {
    ARABIC,
    ARMENIAN,
    DANISH,
    DUTCH,
    ENGLISH,
    FINNISH,
    FRENCH,
    GERMAN,
    GREEK,
    HUNGARIAN,
    ITALIAN,
    NORWEGIAN,
    PORTUGUESE,
    ROMANIAN,
    RUSSIAN,
    SPANISH,
    SWEDISH,
    TAMIL,
    TURKISH,
    ;

    /** Qdrant spells these lowercase. */
    internal val wire: String get() = name.lowercase()
}

/**
 * Write-only serializer for Qdrant's two shapes: `{"type":"snowball","language":"italian"}` and
 * `{"type":"none"}`.
 */
internal object StemmingAlgorithmSerializer : KSerializer<StemmingAlgorithm> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.StemmingAlgorithm")

    override fun serialize(encoder: Encoder, value: StemmingAlgorithm) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("StemmingAlgorithm can only be serialized to JSON")
        json.encodeJsonElement(
            when (value) {
                is StemmingAlgorithm.Snowball -> buildJsonObject {
                    put("type", "snowball")
                    put("language", JsonPrimitive(value.language.wire))
                }
                StemmingAlgorithm.Disabled -> buildJsonObject { put("type", "none") }
            },
        )
    }

    override fun deserialize(decoder: Decoder): StemmingAlgorithm =
        throw SerializationException("StemmingAlgorithm is request-only and is never deserialized")
}
