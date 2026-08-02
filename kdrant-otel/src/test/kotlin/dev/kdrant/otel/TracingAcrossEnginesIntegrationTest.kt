package dev.kdrant.otel

import dev.kdrant.QdrantClient
import dev.kdrant.model.Distance
import dev.kdrant.transport.grpc.KdrantGrpc
import dev.kdrant.transport.rest.Kdrant
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer

/**
 * The claim this module rests on: one decorator, two engines, the same trace.
 *
 * A tracing implementation written per engine drifts, and the drift is invisible until someone
 * compares two services in the same trace view. So the same search is run over REST and over gRPC and
 * the spans are compared attribute by attribute — which only means anything against a real Qdrant,
 * because a mock would let both engines agree on a request neither could actually make.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracingAcrossEnginesIntegrationTest {

    private val spans = InMemorySpanExporter.create()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .build()

    private lateinit var container: QdrantContainer
    private lateinit var rest: QdrantClient
    private lateinit var grpc: QdrantClient

    @BeforeAll
    fun startQdrant() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the cross-engine tracing test",
        )
        container = QdrantContainer(IMAGE).also { it.start() }
        rest = Kdrant(
            host = container.host,
            port = container.getMappedPort(6333),
            decorateTransport = kdrantTracing(openTelemetry, container.host, container.getMappedPort(6333)),
        )
        grpc = KdrantGrpc(
            host = container.host,
            port = container.grpcPort,
            decorateTransport = kdrantTracing(openTelemetry, container.host, container.getMappedPort(6333)),
        )
        runBlocking {
            rest.createCollection(COLLECTION) { vector { size = 4; distance = Distance.DOT } }
            rest.upsert(COLLECTION, wait = true) {
                point(1) { vector(1.0f, 0.0f, 0.0f, 0.0f); payload("tenant" to "acme") }
                point(2) { vector(0.0f, 1.0f, 0.0f, 0.0f); payload("tenant" to "globex") }
            }
        }
        spans.reset()
    }

    /** JUnit does not promise an order, and the exporter is shared, so each case starts from empty. */
    @BeforeEach
    fun clearSpans() {
        spans.reset()
    }

    @AfterAll
    fun stopQdrant() {
        if (::grpc.isInitialized) grpc.close()
        if (::rest.isInitialized) rest.close()
        openTelemetry.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    @Test
    fun `the same search over both engines produces the same span`() = runBlocking {
        rest.search(COLLECTION) { query(0.9f, 0.1f, 0.0f, 0.0f); limit = 1 }
        grpc.search(COLLECTION) { query(0.9f, 0.1f, 0.0f, 0.0f); limit = 1 }

        val (overRest, overGrpc) = spans.finishedSpanItems.also {
            assertEquals(2, it.size, "expected one span per engine, got ${it.map(SpanData::getName)}")
        }

        assertEquals("query $COLLECTION", overRest.name)
        assertEquals(overRest.name, overGrpc.name)
        assertEquals(overRest.kind, overGrpc.kind)
        assertEquals(overRest.attributes, overGrpc.attributes)
    }

    @Test
    fun `a filter naming a tenant does not reach the span, over either engine`() = runBlocking {
        rest.count(COLLECTION) { must { "tenant" eq "acme" } }
        grpc.count(COLLECTION) { must { "tenant" eq "acme" } }

        val rendered = spans.finishedSpanItems.joinToString("\n") { it.toString() }
        assertTrue(spans.finishedSpanItems.size == 2, "expected one span per engine")
        assertTrue(!rendered.contains("acme"), "the filter value reached a span: $rendered")
    }

    @Test
    fun `an operation gRPC cannot serve fails without inventing a different span`() = runBlocking {
        runCatching { grpc.telemetry() }

        val span = spans.finishedSpanItems.single()
        assertEquals("telemetry", span.name)
        assertEquals("ERROR", span.status.statusCode.name)
    }

    private companion object {
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
        const val COLLECTION = "traced"
    }
}
