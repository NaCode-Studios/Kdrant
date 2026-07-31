package dev.kdrant.transport.grpc

import dev.kdrant.kdrantConfig
import io.grpc.inprocess.InProcessChannelBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * The eleven operations `QdrantTransport` carries that Qdrant serves over HTTP only.
 *
 * The seam was shaped by the REST API, so it is wider than the gRPC protocol, and the interesting
 * question is not whether these work — they cannot — but what happens when one is called. Each fails
 * loudly, naming itself and naming REST. A snapshot download that returned an empty flow, or a
 * telemetry call that returned an empty object, would be a client agreeing to something it did not do.
 *
 * No server is needed: the failure is decided before anything reaches the wire, which is also what
 * these assertions prove.
 */
class RestOnlyOperationsTest {

    private val transport = GrpcQdrantTransport(
        config = kdrantConfig("localhost", 6334),
        channel = InProcessChannelBuilder.forName("no-server").build(),
        upsertBatchSize = 64,
    )

    @TestFactory
    fun `every operation gRPC does not carry fails by naming itself and REST`(): List<DynamicTest> {
        val operations: Map<String, suspend () -> Unit> = mapOf(
            "telemetry" to { transport.telemetry() },
            "metrics" to { transport.metrics() },
            "listIssues" to { transport.listIssues() },
            "clearIssues" to { transport.clearIssues() },
            "recoverSnapshot" to { transport.recoverSnapshot("c", "file://x", null, null, true) },
            "uploadSnapshot" to { transport.uploadSnapshot("c", emptyFlow(), null, null, true) },
            "createShardSnapshot" to { transport.createShardSnapshot("c", 0, true) },
            "listShardSnapshots" to { transport.listShardSnapshots("c", 0) },
            "deleteShardSnapshot" to { transport.deleteShardSnapshot("c", 0, "s", true) },
            "recoverShardSnapshot" to { transport.recoverShardSnapshot("c", 0, "file://x", null, null, true) },
            "uploadShardSnapshot" to { transport.uploadShardSnapshot("c", 0, emptyFlow(), null, null, true) },
        )
        return operations.map { (name, call) ->
            DynamicTest.dynamicTest(name) {
                runTest {
                    val error = assertThrows<UnsupportedOperationException> { call() }
                    assertTrue(error.message!!.contains(name), error.message)
                    assertTrue(error.message!!.contains("REST"), error.message)
                }
            }
        }
    }

    @TestFactory
    fun `the streaming downloads fail at the call, not on the first collect`(): List<DynamicTest> {
        // These return a Flow rather than suspending, so a lazy failure would surface far from the
        // mistake — at the collector, in whatever coroutine happened to consume it.
        val downloads: Map<String, () -> Flow<ByteArray>> = mapOf(
            "downloadSnapshot" to { transport.downloadSnapshot("c", "s") },
            "downloadShardSnapshot" to { transport.downloadShardSnapshot("c", 0, "s") },
            "downloadStorageSnapshot" to { transport.downloadStorageSnapshot("s") },
        )
        return downloads.map { (name, call) ->
            DynamicTest.dynamicTest(name) {
                val error = assertThrows<UnsupportedOperationException> { call() }
                assertTrue(error.message!!.contains(name), error.message)
            }
        }
    }

    @Test
    fun `an upsert batch size that cannot hold a point is refused at construction`() {
        assertThrows<IllegalArgumentException> {
            GrpcQdrantTransport(
                config = kdrantConfig("localhost", 6334),
                channel = InProcessChannelBuilder.forName("no-server").build(),
                upsertBatchSize = 0,
            )
        }
    }
}
