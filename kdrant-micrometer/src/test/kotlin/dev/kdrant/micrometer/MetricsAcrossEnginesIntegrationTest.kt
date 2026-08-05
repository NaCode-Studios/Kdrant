package dev.kdrant.micrometer

import dev.kdrant.QdrantClient
import dev.kdrant.model.Distance
import dev.kdrant.transport.grpc.KdrantGrpc
import dev.kdrant.transport.rest.Kdrant
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
 * The claim this module rests on after M45: one decorator, two engines, one timer.
 *
 * Before it, metrics were a Ktor client plugin, so a client built with `KdrantGrpc` published nothing
 * and said nothing about it. The failure was silent and the fix has to be checked the same way the
 * tracing one is: run the same operation over both engines against a real Qdrant, and compare the
 * meter ids. A mock would let both engines agree on a request neither could actually make.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsAcrossEnginesIntegrationTest {

    private val registry = SimpleMeterRegistry()

    private lateinit var container: QdrantContainer
    private lateinit var rest: QdrantClient
    private lateinit var grpc: QdrantClient

    @BeforeAll
    fun startQdrant() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the cross-engine metrics test",
        )
        container = QdrantContainer(IMAGE).also { it.start() }
        rest = Kdrant(
            host = container.host,
            port = container.getMappedPort(6333),
            decorateTransport = kdrantMetrics(registry),
        )
        grpc = KdrantGrpc(
            host = container.host,
            port = container.grpcPort,
            decorateTransport = kdrantMetrics(registry),
        )
        runBlocking {
            rest.createCollection(COLLECTION) { vector { size = 4; distance = Distance.DOT } }
            rest.upsert(COLLECTION, wait = true) {
                point(1) { vector(1.0f, 0.0f, 0.0f, 0.0f); payload("tenant" to "acme") }
            }
        }
        registry.clear()
    }

    @AfterAll
    fun stopQdrant() {
        if (::grpc.isInitialized) grpc.close()
        if (::rest.isInitialized) rest.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    @Test
    fun `the same upsert over both engines increments one timer with identical tags`() = runBlocking {
        rest.upsert(COLLECTION, wait = true) { point(2) { vector(0.0f, 1.0f, 0.0f, 0.0f) } }
        grpc.upsert(COLLECTION, wait = true) { point(3) { vector(0.0f, 0.0f, 1.0f, 0.0f) } }

        val upserts = registry.find("kdrant.requests").tag("operation", "upsert").timers()

        assertEquals(1, upserts.size, "two engines produced ${upserts.size} timers: ${upserts.map(Meter::getId)}")
        assertEquals(2, upserts.single().count(), "both engines should have recorded against it")
        assertEquals(
            mapOf("operation" to "upsert", "outcome" to "SUCCESS"),
            upserts.single().id.tags.associate { it.key to it.value },
        )
    }

    @Test
    fun `a search over gRPC is measured, which the Ktor plugin could never do`() = runBlocking {
        grpc.search(COLLECTION) { query(0.9f, 0.1f, 0.0f, 0.0f); limit = 1 }

        val queries = registry.find("kdrant.requests").tag("operation", "query").timers()

        assertEquals(1, queries.size)
        assertTrue(queries.single().count() >= 1)
    }

    @Test
    fun `a collection name never becomes a tag, over either engine`() = runBlocking {
        // Both collections are missing on purpose: what is under test is the meter id, and a failed
        // call produces one just as a successful call does.
        runCatching { rest.count("tenant-acme-0001") }
        runCatching { grpc.count("tenant-acme-0002") }

        val rendered = registry.meters.joinToString("\n") { it.id.toString() }

        assertTrue(!rendered.contains("tenant-acme"), "a collection name reached a meter id: $rendered")
    }

    @Test
    fun `an operation gRPC cannot serve is recorded as the failure the caller sees`() = runBlocking {
        runCatching { grpc.telemetry() }

        val telemetry = registry.find("kdrant.requests").tag("operation", "telemetry").timers().single()

        assertEquals(1, telemetry.count())
        assertTrue(
            telemetry.id.getTag("outcome") != "SUCCESS",
            "a refused operation was recorded as a success",
        )
    }

    private companion object {
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
        const val COLLECTION = "metered"
    }
}
