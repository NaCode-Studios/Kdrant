package dev.kdrant

import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.Distance

/**
 * Fetch a collection's info, or `null` if it does not exist — instead of throwing
 * [KdrantException.CollectionNotFound].
 *
 * ```kotlin
 * val info = qdrant.getCollectionOrNull("articles") ?: return
 * ```
 */
public suspend fun QdrantClient.getCollectionOrNull(name: String): CollectionInfo? =
    try {
        getCollection(name)
    } catch (e: KdrantException.CollectionNotFound) {
        null
    }

/**
 * Create a collection unless it already exists. Returns `true` if it was created, `false` if it
 * already existed.
 *
 * Race-tolerant: it attempts the create and treats the server's [KdrantException.AlreadyExists]
 * (HTTP 409) as "already there", so concurrent callers don't fail — unlike a check-then-create.
 *
 * ```kotlin
 * qdrant.createCollectionIfNotExists("articles") { vector { size = 1_536; distance = Distance.COSINE } }
 * ```
 */
public suspend fun QdrantClient.createCollectionIfNotExists(
    name: String,
    configure: CreateCollectionBuilder.() -> Unit,
): Boolean =
    try {
        createCollection(name, configure)
        true
    } catch (e: KdrantException.AlreadyExists) {
        false
    }

/**
 * Create a single-vector collection from just a size and distance — the common case.
 *
 * ```kotlin
 * qdrant.createCollection("articles", size = 1_536)                 // COSINE
 * qdrant.createCollection("articles", size = 768, Distance.DOT)
 * ```
 */
public suspend fun QdrantClient.createCollection(
    name: String,
    size: Long,
    distance: Distance = Distance.COSINE,
) {
    val vectorSize = size
    val vectorDistance = distance
    createCollection(name) {
        vector {
            this.size = vectorSize
            this.distance = vectorDistance
        }
    }
}
