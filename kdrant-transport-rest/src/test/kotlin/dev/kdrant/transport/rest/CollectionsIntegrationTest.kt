package dev.kdrant.transport.rest

import dev.kdrant.model.Distance
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer

/**
 * End-to-end tests for collection operations against a real Qdrant in Docker.
 * Skipped (not failed) when Docker is unavailable. Runs in CI where Docker is present.
 */
class CollectionsIntegrationTest {

    companion object {
        private val container = QdrantContainer("qdrant/qdrant:v1.18.2")

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            assumeTrue(
                DockerClientFactory.instance().isDockerAvailable,
                "Docker not available; skipping Qdrant integration test",
            )
            container.start()
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            if (container.isRunning) container.close()
        }
    }

    private fun newClient() = Kdrant(host = container.host, port = container.getMappedPort(6333))

    @Test
    fun `create then delete a single-vector collection round-trips`() = runBlocking {
        newClient().use { qdrant ->
            qdrant.createCollection("docs") {
                vector { size = 4; distance = Distance.COSINE }
                onDiskPayload = true
            }
            qdrant.deleteCollection("docs")
        }
    }

    @Test
    fun `create then delete a named-vectors collection round-trips`() = runBlocking {
        newClient().use { qdrant ->
            qdrant.createCollection("multi") {
                namedVector("text") { size = 8; distance = Distance.COSINE }
                namedVector("image") { size = 16; distance = Distance.DOT }
            }
            qdrant.deleteCollection("multi")
        }
    }

    @Test
    fun `create collection then upsert points`() = runBlocking {
        newClient().use { qdrant ->
            qdrant.createCollection("vectors") {
                vector { size = 3; distance = Distance.COSINE }
            }
            qdrant.upsert("vectors", wait = true) {
                point(1) {
                    vector(0.1f, 0.2f, 0.3f)
                    payload("lang" to "it", "year" to 2024)
                }
                point("550e8400-e29b-41d4-a716-446655440000") {
                    vector(0.4f, 0.5f, 0.6f)
                }
            }
            qdrant.deleteCollection("vectors")
        }
    }
}
