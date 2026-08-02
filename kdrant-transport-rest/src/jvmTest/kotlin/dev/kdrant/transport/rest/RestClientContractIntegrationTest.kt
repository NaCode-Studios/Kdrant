package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.testkit.QdrantClientContract
import org.testcontainers.qdrant.QdrantContainer

/**
 * The shared client contract, run over the REST engine. Everything asserted here is asserted about
 * behaviour rather than about HTTP, which is what lets a second engine be held to the same file.
 *
 * The REST-only operations — telemetry, metrics, issues, snapshot transfer and shard-scope snapshots —
 * are not in the contract, because Qdrant's gRPC API has no equivalent. They are covered by this
 * module's own transport tests.
 */
class RestClientContractIntegrationTest : QdrantClientContract() {

    override fun connect(container: QdrantContainer): QdrantClient =
        Kdrant(host = container.host, port = container.getMappedPort(6333))
}
