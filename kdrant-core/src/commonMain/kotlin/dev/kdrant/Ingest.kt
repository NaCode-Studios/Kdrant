package dev.kdrant

import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How far an [ingest] got, and the only thing needed to carry on from there.
 *
 * [acknowledgedPoints] is a count rather than a cursor because that is what can be honoured against an
 * arbitrary [Flow]: a source is replayed from the beginning and the first [acknowledgedPoints] are
 * skipped. It follows that **resuming is only correct if the source emits the same points in the same
 * order on the second run.** A query over a stable table is; a directory listing whose order depends on
 * the filesystem is not, and neither is a stream that skips what a previous run consumed.
 *
 * [lastPointId] is carried so a caller can check that assumption instead of assuming it: re-run the
 * source, take the point at index `acknowledgedPoints - 1`, and if its id is not this one, the source
 * is not replaying and the token does not apply to it.
 *
 * The count only advances over an unbroken prefix. With batches in flight at once, a later batch can be
 * acknowledged while an earlier one is still being retried, and counting that as progress would mean
 * resuming past points the server never wrote.
 */
public data class IngestCheckpoint(
    /** Points the server acknowledged, counted from the start of the source, with no gap before them. */
    public val acknowledgedPoints: Long,
    /** The id of the last point in that prefix, so a caller can verify a replayed source lines up. */
    public val lastPointId: PointId? = null,
)

/**
 * What an [ingest] did.
 *
 * @property checkpoint where it got to. On success this covers every point the source emitted; after a
 *   failure it is the prefix that is safely in the collection.
 * @property batches how many requests were sent, retries included.
 */
public data class IngestReport(
    public val checkpoint: IngestCheckpoint,
    public val batches: Int,
)

/**
 * Load a collection from a source larger than memory, and be able to carry on after being killed.
 *
 * `upsert(name, flow)` sends what it is given, one batch after another, and everything above it is the
 * caller's: how big a batch is, how many are in flight, what happens when one times out, and where to
 * start again after the process died at point four hundred thousand. That code is written once per
 * project, it is written by someone who has just started using a vector database, and it is where the
 * data goes missing.
 *
 * ```kotlin
 * var token = loadCheckpoint()               // null on a first run
 * val report = qdrant.ingest("docs", points, resumeFrom = token) { saveCheckpoint(it) }
 * ```
 *
 * ### What it owns
 *
 * - **Batching**, by point count and by serialized size, so one oversized point cannot make a request
 *   the server refuses.
 * - **Bounded concurrency**: at most [concurrency] requests in flight. Unbounded parallelism is how an
 *   ingest that was fine for raw vectors starts being rejected the day the server does the embedding.
 * - **Per-batch retry**, so one failed batch is retried rather than the stream restarted. Only
 *   [KdrantException.retryable] failures are retried; a malformed point is returned at once, because
 *   retrying it wastes the window in which the rest could still have been written.
 * - **A resume token**, handed to [onCheckpoint] as it advances and returned in [IngestReport].
 *
 * ### What it does not own
 *
 * Storing the token. Where it goes — a file, a row, an object store — is a question about the caller's
 * deployment, and a client that picked one would be wrong somewhere.
 *
 * It does not embed anything either. If the points carry
 * [dev.kdrant.model.VectorData.Inference] the embedding happens on the server, and that is exactly the
 * case where [concurrency] wants to be small: the ingest stops being upload-bound and starts competing
 * for the server's inference capacity.
 *
 * @param points the source. Cold, replayable, and emitted in a stable order if [resumeFrom] is ever
 *   going to be used against it.
 * @param batchSize most points per request.
 * @param maxBatchBytes soft ceiling on a request's serialized size; a batch is flushed before crossing
 *   it. A single point larger than this is still sent, alone, because splitting a point is not a thing.
 * @param concurrency requests in flight at once. `1` makes the whole ingest sequential.
 * @param maxRetriesPerBatch attempts after the first, for a retryable failure.
 * @param retryDelay delay before the first retry; it doubles per attempt.
 * @param resumeFrom a token from an earlier run. Its points are dropped as they arrive rather than
 *   never produced: the source is replayed in full and the acknowledged prefix does not go over the
 *   network again. Where reading the source is itself the expensive part, have the source start after
 *   the checkpoint and pass no token.
 * @param onCheckpoint called as the acknowledged prefix grows, on the ingest's own coroutine. Keep it
 *   quick: it runs between batches, and a slow write here is throughput the ingest does not have.
 * @throws IllegalArgumentException if a bound is not positive.
 * @throws KdrantException if a batch fails and cannot be retried. The exception is thrown after the
 *   last [onCheckpoint] call, so the token in hand is the prefix that was written.
 */
@Suppress("LongParameterList")
public suspend fun QdrantClient.ingest(
    name: String,
    points: Flow<PointStruct>,
    batchSize: Int = 256,
    maxBatchBytes: Int = DEFAULT_INGEST_BATCH_BYTES,
    concurrency: Int = 4,
    maxRetriesPerBatch: Int = 3,
    retryDelay: Duration = 500.milliseconds,
    resumeFrom: IngestCheckpoint? = null,
    onCheckpoint: suspend (IngestCheckpoint) -> Unit = {},
): IngestReport {
    require(batchSize > 0) { "batchSize must be > 0, was $batchSize" }
    require(maxBatchBytes > 0) { "maxBatchBytes must be > 0, was $maxBatchBytes" }
    require(concurrency > 0) { "concurrency must be > 0, was $concurrency" }
    require(maxRetriesPerBatch >= 0) { "maxRetriesPerBatch must be >= 0, was $maxRetriesPerBatch" }

    val start = resumeFrom?.acknowledgedPoints ?: 0L
    require(start >= 0) { "a checkpoint cannot name a negative number of points, was $start" }

    val tracker = IngestProgress(start, onCheckpoint)
    // A rendezvous channel is the whole concurrency control: `concurrency` workers each take one batch
    // at a time, so the producer cannot run ahead of them and memory stays bounded by the batches in
    // flight rather than by the size of the source.
    val queue = Channel<IngestBatch>(capacity = 0)

    coroutineScope {
        repeat(concurrency) {
            launch {
                for (batch in queue) {
                    sendBatch(name, batch, maxRetriesPerBatch, retryDelay, tracker)
                }
            }
        }
        try {
            var index = 0L
            var offset = start
            var buffer = ArrayList<PointStruct>(batchSize)
            var bytes = 0
            points.drop(startIndexOf(start)).collect { point ->
                val size = estimatedSize(point)
                if (buffer.isNotEmpty() && (buffer.size >= batchSize || bytes + size > maxBatchBytes)) {
                    queue.send(IngestBatch(index++, offset, buffer))
                    offset += buffer.size
                    buffer = ArrayList(batchSize)
                    bytes = 0
                }
                buffer.add(point)
                bytes += size
            }
            if (buffer.isNotEmpty()) queue.send(IngestBatch(index, offset, buffer))
        } finally {
            queue.close()
        }
    }

    tracker.failure?.let { throw it }
    return IngestReport(tracker.checkpoint(), tracker.batchesSent)
}

/** Default soft ceiling on a batch body: well under Qdrant's 32 MiB request cap, with room to spare. */
public const val DEFAULT_INGEST_BATCH_BYTES: Int = 8 * 1024 * 1024

/** A batch, and where it sits in the source, which is what makes the acknowledged prefix computable. */
private class IngestBatch(
    val index: Long,
    val startOffset: Long,
    val points: List<PointStruct>,
)

/**
 * Sends one batch, retrying the failures that are worth retrying, and reports the outcome to [tracker].
 *
 * A failure does not throw here. Throwing would cancel the sibling workers mid-request, and a batch
 * cancelled after the server accepted it is a point the checkpoint would not count and the collection
 * would hold. The first failure is recorded, the queue drains, and it is thrown once at the end.
 */
private suspend fun QdrantClient.sendBatch(
    name: String,
    batch: IngestBatch,
    maxRetries: Int,
    retryDelay: Duration,
    tracker: IngestProgress,
) {
    var attempt = 0
    var wait = retryDelay
    while (true) {
        if (tracker.failed) return
        try {
            // The Sequence overload rather than the Flow one: this batch is already bounded and
            // materialized, and the engine re-chunks only if it is over the wire cap, which it is not.
            upsert(name, batch.points.asSequence(), wait = true)
            tracker.acknowledge(batch)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: KdrantException) {
            if (!e.retryable || attempt >= maxRetries) {
                tracker.fail(e)
                return
            }
            attempt++
            delay(wait)
            wait = minOf(wait * 2, MAX_INGEST_RETRY_DELAY)
        }
    }
}

private val MAX_INGEST_RETRY_DELAY = 30.seconds

/**
 * The acknowledged prefix, and the first failure.
 *
 * Batches finish out of order, so an acknowledgement is held until every batch before it has been
 * acknowledged too. That is the difference between a token that means "everything up to here is in the
 * collection" and one that means "some of this is".
 */
private class IngestProgress(
    startOffset: Long,
    private val onCheckpoint: suspend (IngestCheckpoint) -> Unit,
) {
    private val mutex = Mutex()
    private val pending = HashMap<Long, IngestBatch>()
    private var nextExpected = 0L
    private var acknowledged = startOffset
    private var lastId: PointId? = null

    var batchesSent: Int = 0
        private set

    @Volatile
    var failure: KdrantException? = null
        private set

    val failed: Boolean get() = failure != null

    suspend fun acknowledge(batch: IngestBatch) {
        val advanced = mutex.withLock {
            batchesSent++
            pending[batch.index] = batch
            var moved = false
            while (true) {
                val next = pending.remove(nextExpected) ?: break
                acknowledged = next.startOffset + next.points.size
                lastId = next.points.last().id
                nextExpected++
                moved = true
            }
            if (moved) IngestCheckpoint(acknowledged, lastId) else null
        }
        advanced?.let { onCheckpoint(it) }
    }

    suspend fun fail(error: KdrantException) {
        mutex.withLock {
            batchesSent++
            if (failure == null) failure = error
        }
    }

    fun checkpoint(): IngestCheckpoint = IngestCheckpoint(acknowledged, lastId)
}

/**
 * `Flow.drop` counts in `Int`, and an ingest large enough to need a resume token can be larger than
 * that. Refusing loudly beats silently restarting a two-billion-point load from the beginning.
 */
private fun startIndexOf(acknowledged: Long): Int {
    require(acknowledged <= Int.MAX_VALUE) {
        "resuming past ${Int.MAX_VALUE} points is not supported: have the source start after the " +
            "checkpoint instead of skipping into it"
    }
    return acknowledged.toInt()
}

/**
 * A cheap stand-in for the serialized size, used only to decide where to cut a batch.
 *
 * Serializing every point twice — once to measure, once to send — would double the cost of the hot
 * path to make a bound that is a safety margin anyway. A dense vector is the overwhelming majority of a
 * point's bytes and each float costs about a dozen characters as JSON, so counting values is close
 * enough to keep the batch under a cap set well below the server's.
 */
private fun estimatedSize(point: PointStruct): Int {
    val vector = point.vector
    val values = when (vector) {
        is dev.kdrant.model.VectorData.Dense -> vector.values.size
        is dev.kdrant.model.VectorData.DenseArray -> vector.values.size
        is dev.kdrant.model.VectorData.Sparse -> vector.values.size * 2
        is dev.kdrant.model.VectorData.MultiDense -> vector.vectors.sumOf { it.size }
        is dev.kdrant.model.VectorData.Named -> vector.vectors.values.sumOf { nested ->
            when (nested) {
                is dev.kdrant.model.VectorData.Dense -> nested.values.size
                is dev.kdrant.model.VectorData.DenseArray -> nested.values.size
                is dev.kdrant.model.VectorData.Sparse -> nested.values.size * 2
                is dev.kdrant.model.VectorData.MultiDense -> nested.vectors.sumOf { it.size }
                else -> 0
            }
        }
        else -> 0
    }
    val payload = point.payload?.toString()?.length ?: 0
    return values * BYTES_PER_FLOAT_AS_JSON + payload + POINT_OVERHEAD_BYTES
}

private const val BYTES_PER_FLOAT_AS_JSON = 12
private const val POINT_OVERHEAD_BYTES = 64
