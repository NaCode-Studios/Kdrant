package dev.kdrant.internal

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.UpsertBuilder
import dev.kdrant.transport.QdrantTransport

/**
 * Protocol-independent [QdrantClient]: turns the ergonomic DSL into request models and
 * delegates the actual I/O to a [QdrantTransport]. Lives in core (no wire-protocol knowledge).
 */
internal class DefaultQdrantClient(
    private val transport: QdrantTransport,
) : QdrantClient {

    override suspend fun createCollection(
        name: String,
        configure: CreateCollectionBuilder.() -> Unit,
    ) {
        val request = CreateCollectionBuilder().apply(configure).build()
        transport.createCollection(name, request)
    }

    override suspend fun deleteCollection(name: String) {
        transport.deleteCollection(name)
    }

    override suspend fun upsert(
        name: String,
        wait: Boolean,
        configure: UpsertBuilder.() -> Unit,
    ) {
        val points = UpsertBuilder().apply(configure).build()
        transport.upsert(name, points, wait)
    }

    override fun close() {
        transport.close()
    }
}
