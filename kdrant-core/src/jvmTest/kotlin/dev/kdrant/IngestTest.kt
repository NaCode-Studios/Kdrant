package dev.kdrant

import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.VectorData
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * The ingest against a stubbed client. What is under test is the part the caller would otherwise have
 * written: where a batch is cut, how many are in flight, what a retry does, and what the token means
 * after a failure. None of that needs a server, and all of it is what goes wrong at four in the morning.
 */
class IngestTest {

    private val client = mockk<QdrantClient>(relaxed = true)

    private fun points(range: LongRange): Flow<PointStruct> = range.map {
        PointStruct(PointId.num(it), VectorData.Dense(listOf(0.1f, 0.2f, 0.3f, 0.4f)))
    }.asFlow()

    private fun captureBatches(): MutableList<List<PointStruct>> {
        val batches = mutableListOf<List<PointStruct>>()
        coEvery { client.upsert(any<String>(), any<Sequence<PointStruct>>(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            batches.add((secondArg<Sequence<PointStruct>>()).toList())
        }
        return batches
    }

    @Test
    fun `the source is cut into batches of the size that was asked for`() = runTest {
        val batches = captureBatches()

        val report = client.ingest("docs", points(1L..10L), batchSize = 4, concurrency = 1)

        assertEquals(listOf(4, 4, 2), batches.map { it.size })
        assertEquals(10L, report.checkpoint.acknowledgedPoints)
        assertEquals(PointId.num(10), report.checkpoint.lastPointId)
        assertEquals(3, report.batches)
    }

    @Test
    fun `a batch is cut early when it would cross the byte ceiling`() = runTest {
        val batches = captureBatches()

        // Well under the point count, so only the size bound can be what cuts these.
        client.ingest("docs", points(1L..6L), batchSize = 1000, maxBatchBytes = 200, concurrency = 1)

        assertTrue(batches.size > 1, "the size bound never fired: ${batches.map { it.size }}")
        assertEquals(6, batches.sumOf { it.size }, "no point may be dropped at a boundary")
    }

    @Test
    fun `no more than the stated number of requests is ever in flight`() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        coEvery { client.upsert(any<String>(), any<Sequence<PointStruct>>(), any()) } coAnswers {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { seen -> maxOf(seen, now) }
            kotlinx.coroutines.delay(5)
            inFlight.decrementAndGet()
        }

        client.ingest("docs", points(1L..40L), batchSize = 2, concurrency = 3)

        assertTrue(peak.get() <= 3, "peak concurrency was ${peak.get()}, the bound was 3")
    }

    @Test
    fun `a retryable batch failure is retried rather than restarting the stream`() = runTest {
        var attempts = 0
        coEvery { client.upsert(any<String>(), any<Sequence<PointStruct>>(), any()) } answers {
            attempts++
            if (attempts == 1) throw KdrantException.ServiceUnavailable() else Unit
        }

        val report = client.ingest(
            "docs",
            points(1L..4L),
            batchSize = 4,
            concurrency = 1,
            retryDelay = 1.milliseconds,
        )

        assertEquals(2, attempts, "the failed batch should have been sent again")
        assertEquals(4L, report.checkpoint.acknowledgedPoints)
    }

    @Test
    fun `a terminal failure is not retried, because the same bytes would be refused again`() = runTest {
        var attempts = 0
        coEvery { client.upsert(any<String>(), any<Sequence<PointStruct>>(), any()) } answers {
            attempts++
            throw KdrantException.InvalidRequest("Wrong input: Vector dimension error")
        }

        assertThrows(KdrantException.InvalidRequest::class.java) {
            kotlinx.coroutines.runBlocking {
                client.ingest("docs", points(1L..4L), batchSize = 4, concurrency = 1, retryDelay = 1.milliseconds)
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `the token names only the prefix the server acknowledged, never past a hole`() = runTest {
        // The second batch fails; the third and fourth succeed. Counting them would resume past points
        // that were never written, which is the whole reason the prefix is contiguous.
        coEvery { client.upsert(any<String>(), any<Sequence<PointStruct>>(), any()) } answers {
            val ids = secondArg<Sequence<PointStruct>>().map { (it.id as PointId.Num).value }.toList()
            if (3UL in ids) throw KdrantException.InvalidRequest("refused") else Unit
        }
        val seen = mutableListOf<IngestCheckpoint>()

        val failure = runCatching {
            client.ingest(
                "docs",
                points(1L..8L),
                batchSize = 2,
                concurrency = 1,
                retryDelay = 1.milliseconds,
                onCheckpoint = { seen.add(it) },
            )
        }.exceptionOrNull()

        assertTrue(failure is KdrantException.InvalidRequest)
        assertEquals(listOf(2L), seen.map { it.acknowledgedPoints })
    }

    @Test
    fun `a source that dies mid-stream still hands out the batches that were in flight`() = runTest {
        // The first batch is still on the wire when the source dies. Abandoning it there loses the only
        // token the run ever had, and a process killed at point four hundred thousand starts from zero.
        var firstBatch = true
        coEvery { client.upsert(any<String>(), any<Sequence<PointStruct>>(), any()) } coAnswers {
            if (firstBatch) {
                firstBatch = false
                kotlinx.coroutines.delay(50)
            }
        }
        val seen = mutableListOf<IngestCheckpoint>()

        val source = flow {
            points(1L..8L).collect { emit(it) }
            error("the source died at point 9")
        }
        val failure = runCatching {
            client.ingest("docs", source, batchSize = 2, concurrency = 2, onCheckpoint = { seen.add(it) })
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "the source's own failure is what the caller is told")
        assertEquals(listOf(6L), seen.map { it.acknowledgedPoints }, "the in-flight batch never reported")
    }

    @Test
    fun `a resumed run skips what the token says was written`() = runTest {
        val batches = captureBatches()

        client.ingest(
            "docs",
            points(1L..10L),
            batchSize = 4,
            concurrency = 1,
            resumeFrom = IngestCheckpoint(acknowledgedPoints = 6, lastPointId = PointId.num(6)),
        )

        assertEquals(listOf(PointId.num(7), PointId.num(8), PointId.num(9), PointId.num(10)), batches.flatten().map { it.id })
    }

    @Test
    fun `a resumed run reports the total, not what this run happened to send`() = runTest {
        captureBatches()

        val report = client.ingest(
            "docs",
            points(1L..10L),
            batchSize = 4,
            concurrency = 1,
            resumeFrom = IngestCheckpoint(acknowledgedPoints = 6),
        )

        assertEquals(10L, report.checkpoint.acknowledgedPoints, "a token is only useful if it is absolute")
    }

    @Test
    fun `a bound that is not positive is refused rather than silently corrected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client.ingest("docs", points(1L..2L), batchSize = 0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client.ingest("docs", points(1L..2L), concurrency = 0) }
        }
    }

    @Test
    fun `an empty source writes nothing and still returns a usable token`() = runTest {
        val batches = captureBatches()

        val report = client.ingest("docs", emptyList<PointStruct>().asFlow())

        assertTrue(batches.isEmpty())
        assertEquals(IngestCheckpoint(0, null), report.checkpoint)
    }
}
