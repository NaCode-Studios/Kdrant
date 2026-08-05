package dev.kdrant.transport.rest

import dev.kdrant.KdrantException
import dev.kdrant.kdrantConfig
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.VectorData
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The degraded states, mapped from the exact bytes Qdrant answers with.
 *
 * `RestDegradedClusterIntegrationTest` provokes these against a real two-node cluster, which is the
 * proof; this is the part that runs everywhere and pins which message becomes which exception, so a
 * change to the mapping fails a build rather than a runbook.
 */
class DegradedStateMappingTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun transport(
        status: HttpStatusCode,
        error: String,
    ) = RestQdrantTransport(
        kdrantConfig("h", 6333) { maxRetries = 0 },
        MockEngine { respond("""{"status":{"error":"$error"},"time":0.0}""", status, jsonHeaders) },
    )

    private fun failureOf(status: HttpStatusCode, error: String): Throwable? =
        transport(status, error).use { runCatching { runBlocking { it.count("docs", null, true) } }.exceptionOrNull() }

    @Test
    fun `a 403 naming read-only is the node refusing writes, not the credential being refused`() {
        val failure = failureOf(HttpStatusCode.Forbidden, "Service is in read-only mode")

        assertInstanceOf(KdrantException.ReadOnly::class.java, failure)
        assertTrue((failure as KdrantException).retryable)
    }

    @Test
    fun `a 403 about the credential stays Forbidden and stays terminal`() {
        val failure = failureOf(HttpStatusCode.Forbidden, "Write access denied for this API key")

        assertInstanceOf(KdrantException.Forbidden::class.java, failure)
        assertFalse(failure is KdrantException.ReadOnly)
        assertFalse((failure as KdrantException).retryable)
    }

    @Test
    fun `a strict-mode disk ceiling is the same state with the cause named`() {
        val failure = failureOf(
            HttpStatusCode.BadRequest,
            "Strict mode: disk usage exceeds the configured limit of 0 percent",
        )

        assertInstanceOf(KdrantException.ReadOnly::class.java, failure)
    }

    @Test
    fun `a shard with no live replica is neither a bad request nor a missing collection`() {
        val clientSide = failureOf(HttpStatusCode.BadRequest, "Not enough replicas of shard 1 are available")
        val serverSide = failureOf(HttpStatusCode.InternalServerError, "Shard 1 is not available")

        assertInstanceOf(KdrantException.ShardUnavailable::class.java, clientSide)
        assertInstanceOf(KdrantException.ShardUnavailable::class.java, serverSide)
        assertTrue((clientSide as KdrantException).retryable)
    }

    @Test
    fun `the phrasings a degraded cluster actually uses all reach ShardUnavailable`() {
        // Both halves are required, so a plain server error that happens to say "failed" does not become
        // a cluster diagnosis, and a shard key that is malformed does not either.
        listOf(
            "Not enough replicas of shard 1 are available",
            "No replica available for shard 1",
            "Shard 1 has no active replicas",
            "Service internal error: shard 1 is dead",
            "Failed to read from shard 1",
            "Cannot resolve replica for shard 0",
            // The one a stopped peer actually produces, which names no shard at all. Taken verbatim
            // from a CI run against a two-node cluster with the second node stopped.
            "Service internal error: 1 of 1 read operations failed: Service internal error: Tonic " +
                "status error: code: 'The service is currently unavailable', message: 'dns error'",
        ).forEach { error ->
            assertInstanceOf(
                KdrantException.ShardUnavailable::class.java,
                failureOf(HttpStatusCode.InternalServerError, error),
                "'$error' should have been read as an unavailable shard",
            )
        }

        listOf(
            "Service internal error: failed to flush",
            "Wrong input: shard key 'eu-west' is not a valid key",
            "Service internal error: 1 of 1 read operations failed: index out of bounds",
        ).forEach { error ->
            assertFalse(
                failureOf(HttpStatusCode.InternalServerError, error) is KdrantException.ShardUnavailable,
                "'$error' is not a degraded cluster and must not be read as one",
            )
        }
    }

    @Test
    fun `an unrecognised message keeps the mapping it had before these states existed`() {
        assertInstanceOf(
            KdrantException.Forbidden::class.java,
            failureOf(HttpStatusCode.Forbidden, "something new"),
        )
        assertInstanceOf(
            KdrantException.ServerError::class.java,
            failureOf(HttpStatusCode.InternalServerError, "something new"),
        )
    }

    @Test
    fun `an upsert that fails after an earlier batch landed names the points that were written`() {
        var call = 0
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) { maxRetries = 0 },
            MockEngine {
                call++
                if (call == 1) {
                    respond("""{"result":{"status":"completed"},"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(
                        """{"status":{"error":"Service is in read-only mode"},"time":0.0}""",
                        HttpStatusCode.Forbidden,
                        jsonHeaders,
                    )
                }
            },
            upsertBatchSize = 2,
        )

        val failure = transport.use {
            runCatching { runBlocking { it.upsert("docs", points(5), wait = true) } }.exceptionOrNull()
        }

        val partial = assertInstanceOf(KdrantException.PartiallyApplied::class.java, failure)
        assertEquals(2, partial.applied, "the first batch of two landed before the second was refused")
        assertInstanceOf(KdrantException.ReadOnly::class.java, partial.cause)
        assertTrue(partial.retryable, "the cause was retryable, so the partial write is too")
    }

    @Test
    fun `an upsert that fails on its first batch is reported as the failure itself`() {
        // Nothing was applied, so calling it "partially applied" would be a lie that costs the caller
        // a needless re-send.
        val transport = RestQdrantTransport(
            kdrantConfig("h", 6333) { maxRetries = 0 },
            MockEngine {
                respond(
                    """{"status":{"error":"Wrong input: Vector dimension error"},"time":0.0}""",
                    HttpStatusCode.BadRequest,
                    jsonHeaders,
                )
            },
            upsertBatchSize = 2,
        )

        val failure = transport.use {
            runCatching { runBlocking { it.upsert("docs", points(5), wait = true) } }.exceptionOrNull()
        }

        assertInstanceOf(KdrantException.InvalidRequest::class.java, failure)
    }

    private fun points(count: Int): List<PointStruct> = (1..count).map {
        PointStruct(PointId.num(it.toLong()), VectorData.Dense(listOf(0.1f, 0.2f)))
    }
}
