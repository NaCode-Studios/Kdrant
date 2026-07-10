package dev.kdrant

import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.internal.DefaultQdrantClient
import dev.kdrant.transport.QdrantTransport

/**
 * The user-facing Kdrant API. Coroutine-first: every operation is a `suspend` function.
 *
 * Obtain an instance from a transport factory, e.g. `Kdrant(host, port) { }` in
 * `kdrant-transport-rest`. Implements [AutoCloseable] — use it with `use { }`.
 *
 * Every operation performs network I/O and may throw a [KdrantException].
 */
public interface QdrantClient : AutoCloseable {

    /**
     * Create a collection.
     *
     * ```kotlin
     * qdrant.createCollection("docs") {
     *     vector { size = 768; distance = Distance.COSINE }
     *     onDiskPayload = true
     * }
     * ```
     *
     * Creating a collection that already exists is rejected by the server, not treated as a no-op.
     *
     * @param name the collection name.
     * @param configure builds the collection settings (vectors, HNSW, on-disk payload, ...).
     * @throws KdrantException.InvalidRequest if the collection already exists or the settings are invalid.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun createCollection(name: String, configure: CreateCollectionBuilder.() -> Unit)

    /**
     * Delete a collection. Deleting a collection that does not exist is a no-op on the server.
     *
     * @param name the collection name.
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if the request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun deleteCollection(name: String)

    /**
     * Upsert points into a collection.
     *
     * ```kotlin
     * qdrant.upsert("docs", wait = true) {
     *     point(id = 1) {
     *         vector(0.05f, 0.61f, 0.76f)
     *         payload("title" to "Intro", "lang" to "it")
     *     }
     * }
     * ```
     *
     * The transport may split a large batch into several sequential requests, which is **not
     * atomic**: if a later request fails, earlier points are already applied. Upsert is idempotent
     * per point id, so retrying the whole call is safe.
     *
     * @param name the collection name.
     * @param wait if true, return only once the change is applied; if false (default) it is queued.
     * @param configure builds the points to upsert.
     * @throws KdrantException.CollectionNotFound if the collection does not exist.
     * @throws KdrantException.InvalidRequest if a point is malformed (e.g. wrong vector size).
     * @throws KdrantException.Unauthorized if the API key is missing or wrong.
     * @throws KdrantException.Timeout if a request exceeds the configured timeout.
     * @throws KdrantException.Transport on a connection failure or server error.
     */
    public suspend fun upsert(name: String, wait: Boolean = false, configure: UpsertBuilder.() -> Unit)
}

/** Wraps a [QdrantTransport] into a [QdrantClient]. Used by transport factories. */
public fun QdrantClient(transport: QdrantTransport): QdrantClient = DefaultQdrantClient(transport)
