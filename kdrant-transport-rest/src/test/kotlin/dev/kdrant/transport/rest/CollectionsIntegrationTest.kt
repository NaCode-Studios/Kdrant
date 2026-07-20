package dev.kdrant.transport.rest

import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.Distance
import dev.kdrant.model.PointId
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        // Image is overridable so CI can run this suite against a matrix of Qdrant versions.
        private val image = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
        private val container = QdrantContainer(image)

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

    @Test
    fun `search, scroll and delete round-trip against a real server`() = runBlocking {
        newClient().use { qdrant ->
            qdrant.createCollection("rag") { vector { size = 4; distance = Distance.COSINE } }
            qdrant.upsert("rag", wait = true) {
                point(1) { vector(0.1f, 0.2f, 0.3f, 0.4f); payload("lang" to "en") }
                point(2) { vector(0.2f, 0.1f, 0.4f, 0.3f); payload("lang" to "it") }
            }

            val hits = qdrant.search("rag") {
                query(0.1f, 0.2f, 0.3f, 0.4f)
                limit = 5
                withPayload = WithPayload.All
            }
            assertTrue(hits.isNotEmpty(), "search returned no hits")

            val all = qdrant.scroll("rag", pageSize = 1).map { it.id }.toList()
            assertEquals(2, all.size, "scroll should return both points")

            qdrant.delete("rag", wait = true) { must { "lang" eq "it" } }
            val remaining = qdrant.scroll("rag").toList()
            assertEquals(1, remaining.size, "one point should remain after delete-by-filter")

            qdrant.deleteCollection("rag")
        }
    }

    @Test
    fun `exists, info, count and retrieve against a real server`() = runBlocking {
        newClient().use { qdrant ->
            assertFalse(qdrant.collectionExists("m9"))
            qdrant.createCollection("m9") { vector { size = 3; distance = Distance.COSINE } }
            assertTrue(qdrant.collectionExists("m9"))
            assertEquals(CollectionStatus.GREEN, qdrant.getCollection("m9").status)

            qdrant.upsert("m9", wait = true) {
                point(1) { vector(0.1f, 0.2f, 0.3f) }
                point(2) { vector(0.4f, 0.5f, 0.6f) }
            }
            assertEquals(2L, qdrant.count("m9"))
            assertEquals(0L, qdrant.count("m9") { must { "missing" eq "x" } })

            val records = qdrant.retrieve("m9", ids = listOf(PointId.num(1)))
            assertEquals(1, records.size)
            assertEquals(PointId.num(1), records[0].id)

            qdrant.deleteCollection("m9")
        }
    }
}
