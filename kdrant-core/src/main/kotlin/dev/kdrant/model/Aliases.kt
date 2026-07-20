package dev.kdrant.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
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

/** One alias→collection mapping, as returned by `listAliases` / `listCollectionAliases`. */
@Serializable
public data class AliasDescription(
    @SerialName("alias_name")
    public val aliasName: String,
    @SerialName("collection_name")
    public val collectionName: String,
)

/**
 * A single change applied by `updateAliases`. All operations in one call are applied atomically,
 * which is what makes a zero-downtime reindex possible (build a new collection, then swap the alias).
 *
 * Build these through the `updateAliases { }` DSL rather than constructing them directly.
 */
@Serializable(with = AliasOperationSerializer::class)
public sealed interface AliasOperation {

    /** Point [aliasName] at [collectionName] (creating the alias, or moving an existing one). */
    public data class Create(
        public val collectionName: String,
        public val aliasName: String,
    ) : AliasOperation

    /** Remove [aliasName] if it exists. */
    public data class Delete(
        public val aliasName: String,
    ) : AliasOperation

    /** Atomically rename [oldAliasName] to [newAliasName]. */
    public data class Rename(
        public val oldAliasName: String,
        public val newAliasName: String,
    ) : AliasOperation
}

/** Write-only serializer emitting the `{"create_alias":{…}}` / `{"delete_alias":{…}}` / `{"rename_alias":{…}}` shapes. */
internal object AliasOperationSerializer : KSerializer<AliasOperation> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.kdrant.model.AliasOperation")

    override fun serialize(encoder: Encoder, value: AliasOperation) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("AliasOperation can only be serialized to JSON")
        val element = when (value) {
            is AliasOperation.Create -> buildJsonObject {
                putJsonObject("create_alias") {
                    put("collection_name", value.collectionName)
                    put("alias_name", value.aliasName)
                }
            }
            is AliasOperation.Delete -> buildJsonObject {
                putJsonObject("delete_alias") {
                    put("alias_name", value.aliasName)
                }
            }
            is AliasOperation.Rename -> buildJsonObject {
                putJsonObject("rename_alias") {
                    put("old_alias_name", value.oldAliasName)
                    put("new_alias_name", value.newAliasName)
                }
            }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): AliasOperation =
        throw SerializationException("AliasOperation is request-only and is never deserialized")
}
