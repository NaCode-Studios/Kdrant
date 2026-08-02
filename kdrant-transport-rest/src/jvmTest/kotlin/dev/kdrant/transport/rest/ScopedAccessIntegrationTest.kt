package dev.kdrant.transport.rest

import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.model.Distance
import dev.kdrant.testkit.QdrantJwt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer

/**
 * M39 against a real Qdrant with JWT access control switched on.
 *
 * The point of a scoped token is that the server enforces the scope, so a mock cannot prove it. This
 * starts a node with an API key and `JWT_RBAC`, mints a read-only token signed with that key, and
 * checks that the token reads and does not write — and that the refusal arrives as something a caller
 * can act on rather than as a transport failure.
 *
 * The client talks to the container over loopback without TLS, which the config allows precisely
 * because nothing leaves the machine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopedAccessIntegrationTest {

    private lateinit var container: QdrantContainer
    private lateinit var master: QdrantClient
    private lateinit var readOnly: QdrantClient

    @BeforeAll
    fun startQdrant() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the scoped-access integration test",
        )
        container = QdrantContainer(IMAGE)
            .withEnv("QDRANT__SERVICE__API_KEY", API_KEY)
            .withEnv("QDRANT__SERVICE__JWT_RBAC", "true")
            .also { it.start() }
        assumeTrue(
            container.host == "localhost" || container.host.startsWith("127."),
            "the container is not on loopback, so a plaintext credential would be refused by design",
        )

        master = Kdrant(host = container.host, port = container.getMappedPort(6333)) { apiKey = API_KEY }
        readOnly = Kdrant(host = container.host, port = container.getMappedPort(6333)) {
            bearerToken = QdrantJwt.readOnly(API_KEY)
        }

        runBlocking {
            master.createCollection(COLLECTION) { vector { size = 4; distance = Distance.DOT } }
            master.upsert(COLLECTION, wait = true) {
                point(1) { vector(1.0f, 0.0f, 0.0f, 0.0f); payload("lang" to "it") }
                point(2) { vector(0.0f, 1.0f, 0.0f, 0.0f); payload("lang" to "en") }
            }
        }
    }

    @AfterAll
    fun stopQdrant() {
        if (::readOnly.isInitialized) readOnly.close()
        if (::master.isInitialized) master.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    @Test
    fun `a read-only token completes a search`() = runBlocking {
        val hits = readOnly.search(COLLECTION) { query(0.9f, 0.1f, 0.0f, 0.0f); limit = 1 }

        assertEquals(1, hits.size)
    }

    @Test
    fun `a read-only token is refused on a write, with the reason attached`() = runBlocking {
        val error = runCatching {
            readOnly.upsert(COLLECTION, wait = true) { point(3) { vector(0.0f, 0.0f, 1.0f, 0.0f) } }
        }.exceptionOrNull()

        assertTrue(
            error is KdrantException.Forbidden,
            "expected Forbidden, got ${error?.let { it::class.simpleName + ": " + it.message }}",
        )
        // The master key still sees two points: the refusal was the server's, not the client's.
        assertEquals(2L, master.count(COLLECTION))
    }

    @Test
    fun `an unsigned request is unauthorized rather than forbidden`() = runBlocking {
        Kdrant(host = container.host, port = container.getMappedPort(6333)).use { anonymous ->
            val error = runCatching { anonymous.count(COLLECTION) }.exceptionOrNull()

            assertTrue(
                error is KdrantException.Unauthorized && error !is KdrantException.Forbidden,
                "expected Unauthorized, got ${error?.let { it::class.simpleName + ": " + it.message }}",
            )
        }
    }

    private companion object {
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
        const val API_KEY = "contract-master-key"
        const val COLLECTION = "scoped-access"
    }
}
