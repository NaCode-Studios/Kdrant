package dev.kdrant.otel

import dev.kdrant.KdrantException
import dev.kdrant.dsl.payloadOf
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.PointId
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.SearchRequest
import dev.kdrant.transport.QdrantTransport
import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the spans say, and — the part that matters more — what they never say.
 */
class TracingQdrantTransportTest {

    private val spans = InMemorySpanExporter.create()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .build()

    @AfterEach
    fun reset() {
        spans.reset()
        openTelemetry.close()
    }

    private fun traced(configure: QdrantTransport.() -> Unit = {}): QdrantTransport {
        val delegate = mockk<QdrantTransport>(relaxed = true).apply(configure)
        return delegate.traced(openTelemetry, serverAddress = "qdrant.internal", serverPort = 6333)
    }

    @Test
    fun `a search opens one client span named after the operation and the collection`() = runTest {
        traced { coEvery { query(any(), any()) } returns emptyList() }
            .query("docs", SearchRequest(query = QueryInterface.Vector(listOf(0.1f, 0.2f)), limit = 10))

        val span = spans.finishedSpanItems.single()
        assertEquals("query docs", span.name)
        assertEquals("CLIENT", span.kind.name)
        assertEquals("qdrant", span.attributes.get(KdrantAttributes.DB_SYSTEM_NAME))
        assertEquals("query", span.attributes.get(KdrantAttributes.DB_OPERATION_NAME))
        assertEquals("docs", span.attributes.get(KdrantAttributes.DB_COLLECTION_NAME))
        assertEquals("qdrant.internal", span.attributes.get(KdrantAttributes.SERVER_ADDRESS))
        assertEquals(6333L, span.attributes.get(KdrantAttributes.SERVER_PORT))
    }

    @Test
    fun `an operation that names no collection is named after itself alone`() = runTest {
        traced { coEvery { listCollections() } returns emptyList() }.listCollections()

        val span = spans.finishedSpanItems.single()
        assertEquals("list_collections", span.name)
        assertFalse(
            span.attributes.asMap().keys.any { it.key == KdrantAttributes.DB_COLLECTION_NAME.key },
            "there is no collection, so there should be no collection attribute",
        )
    }

    @Test
    fun `the attribute set is closed, so no payload, vector or filter can reach an exporter`() = runTest {
        val transport = traced {
            coEvery { query(any(), any()) } returns emptyList()
            coEvery { count(any(), any(), any()) } returns 0L
        }
        transport.query(
            "tenants",
            SearchRequest(query = QueryInterface.Vector(listOf(0.42f, 0.43f)), limit = 3),
        )
        transport.setPayload(
            "tenants",
            payloadOf("tenant" to "acme", "secret" to "do not export me"),
            DeleteSelector.Ids(listOf(PointId.num(1))),
            key = null,
            wait = true,
        )

        val keys = spans.finishedSpanItems.flatMap { it.attributes.asMap().keys.map { key -> key.key } }.toSet()
        assertEquals(
            setOf("db.system.name", "db.operation.name", "db.collection.name", "server.address", "server.port"),
            keys,
        )
        val rendered = spans.finishedSpanItems.joinToString("\n") { it.toString() }
        assertFalse(rendered.contains("acme"), "a payload value reached a span")
        assertFalse(rendered.contains("do not export me"), "a payload value reached a span")
        assertFalse(rendered.contains("0.42"), "a vector component reached a span")
    }

    @Test
    fun `a failure marks the span with the exception type and not with the server's message`() = runTest {
        val transport = traced {
            coEvery { getCollection(any()) } throws
                KdrantException.CollectionNotFound("docs", "no such collection: docs")
        }

        runCatching { transport.getCollection("docs") }

        val span = spans.finishedSpanItems.single()
        assertEquals("ERROR", span.status.statusCode.name)
        assertEquals(
            "dev.kdrant.KdrantException.CollectionNotFound",
            span.attributes.get(KdrantAttributes.ERROR_TYPE),
        )
        assertFalse(
            span.toString().contains("no such collection"),
            "Qdrant quotes the request back in its errors, so the message stays out of the span",
        )
    }

    @Test
    fun `close is not an operation and opens no span`() = runTest {
        traced().close()

        assertTrue(spans.finishedSpanItems.isEmpty())
    }

    @Test
    fun `kdrantTracing produces the same decorator as traced`() = runTest {
        val delegate = mockk<QdrantTransport>(relaxed = true).apply {
            coEvery { healthz() } returns true
        }

        kdrantTracing(openTelemetry)(delegate).healthz()

        assertEquals("healthz", spans.finishedSpanItems.single().name)
    }
}
