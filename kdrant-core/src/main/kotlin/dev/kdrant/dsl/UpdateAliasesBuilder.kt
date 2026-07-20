package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.AliasOperation

/**
 * DSL for `updateAliases`. Every action added here is applied by the server as one atomic batch,
 * so an alias never points at nothing during a swap.
 *
 * ```kotlin
 * qdrant.updateAliases {
 *     deleteAlias("docs")            // detach the old collection
 *     createAlias("docs-v2", "docs") // and re-point in the same atomic step
 * }
 * ```
 */
@KdrantDsl
public class UpdateAliasesBuilder {

    private val operations = mutableListOf<AliasOperation>()

    /** Point [alias] at [collection] (creates the alias, or moves it if it already exists). */
    public fun createAlias(collection: String, alias: String) {
        operations += AliasOperation.Create(collectionName = collection, aliasName = alias)
    }

    /** Remove [alias] if it exists. */
    public fun deleteAlias(alias: String) {
        operations += AliasOperation.Delete(aliasName = alias)
    }

    /** Rename alias [from] to [to]. */
    public fun renameAlias(from: String, to: String) {
        operations += AliasOperation.Rename(oldAliasName = from, newAliasName = to)
    }

    internal fun build(): List<AliasOperation> = operations.toList()
}
