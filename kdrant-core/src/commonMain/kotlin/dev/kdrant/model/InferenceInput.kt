package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * What the **server** should embed, and with which model.
 *
 * Kdrant does not generate embeddings: it bundles no model, takes no dependency on an inference
 * library, and never sends a vector it computed itself. This type is not an exception to that. It is a
 * request that names text, an image or a custom object together with a model, exactly as a filter names
 * what to match, and Qdrant produces the vector on its own side. The models, the providers and the cost
 * of running them stay where they are.
 *
 * ```kotlin
 * qdrant.upsert("docs", wait = true) {
 *     point(1) { document("the text to embed", model = "jinaai/jina-embeddings-v2-base-en") }
 * }
 * val hits = qdrant.search("docs") {
 *     query(InferenceInput.Document("what to look for", model = "jinaai/jina-embeddings-v2-base-en"))
 * }
 * ```
 *
 * The request needs a Qdrant with an inference provider configured; a plain container has none and
 * rejects it. That is the server's deployment rather than the client's capability, which is why this
 * is a request shape the contract tests validate against Qdrant's own schema and a round trip that
 * runs only where a provider exists.
 *
 * The three shapes are distinguished by the field they carry — `text`, `image`, `object` — rather than
 * by a discriminator, which is why they serialize through a hand-written serializer instead of the
 * generated polymorphic one.
 */
@Serializable(with = InferenceInputSerializer::class)
public sealed interface InferenceInput {

    /** The model that produces the vector. Which names are valid depends on the server's provider. */
    public val model: String

    /** Model-specific options, passed to the inference service as they are. */
    public val options: Map<String, JsonElement>?

    /** Text to embed. */
    @Serializable
    public data class Document(
        @SerialName("text") public val text: String,
        @SerialName("model") override val model: String,
        @SerialName("options") override val options: Map<String, JsonElement>? = null,
    ) : InferenceInput

    /**
     * An image to embed, as a URL or as base64-encoded bytes. [image] is untyped because Qdrant accepts
     * either, and which one a provider wants is the provider's business.
     */
    @Serializable
    public data class Image(
        @SerialName("image") public val image: JsonElement,
        @SerialName("model") override val model: String,
        @SerialName("options") override val options: Map<String, JsonElement>? = null,
    ) : InferenceInput

    /** Arbitrary input, for a model that takes something other than one document or one image. */
    @Serializable
    public data class Custom(
        @SerialName("object") public val value: JsonElement,
        @SerialName("model") override val model: String,
        @SerialName("options") override val options: Map<String, JsonElement>? = null,
    ) : InferenceInput
}

/**
 * Writes each shape as the bare object Qdrant expects, with no class discriminator: the server reads
 * `text`, `image` or `object` to know which one it received, and an extra key would be a field it did
 * not ask for.
 *
 * Request-only, like [QueryInterface]. Qdrant answers with the vector it computed, never with the
 * request that asked for it.
 */
internal object InferenceInputSerializer : KSerializer<InferenceInput> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.InferenceInput")

    override fun serialize(encoder: Encoder, value: InferenceInput) {
        when (value) {
            is InferenceInput.Document ->
                encoder.encodeSerializableValue(InferenceInput.Document.serializer(), value)

            is InferenceInput.Image ->
                encoder.encodeSerializableValue(InferenceInput.Image.serializer(), value)

            is InferenceInput.Custom ->
                encoder.encodeSerializableValue(InferenceInput.Custom.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): InferenceInput =
        throw SerializationException("InferenceInput is request-only and is never deserialized")
}
