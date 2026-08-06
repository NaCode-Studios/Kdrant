package dev.kdrant.transport.grpc

import dev.kdrant.KdrantException
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException

/**
 * gRPC status codes onto the same [KdrantException] hierarchy the REST engine raises, so a caller can
 * swap engines without rewriting a `when`.
 *
 * Two codes need more than a table. `NOT_FOUND` is what Qdrant answers for a missing collection, but
 * it is also what an unimplemented path would answer, so the collection name is carried in rather than
 * parsed out of the message. And `INVALID_ARGUMENT` is what Qdrant returns for a collection that does
 * not exist on some write paths, which is why the message is checked as well as the code.
 *
 * [CancellationException] is re-thrown untouched. gRPC reports a cancelled call as `Status.CANCELLED`,
 * and turning that into a `KdrantException` would break structured concurrency: the coroutine that was
 * cancelled would see a failure instead of its own cancellation.
 */
internal object GrpcErrors {

    /** Runs [block], translating any gRPC failure. [collection] names the collection the call was about. */
    suspend fun <T> mapping(collection: String?, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StatusRuntimeException) {
            throw translate(e.status, e.status.description, collection, e)
        } catch (e: StatusException) {
            throw translate(e.status, e.status.description, collection, e)
        }

    private fun translate(
        status: Status,
        description: String?,
        collection: String?,
        cause: Throwable,
    ): KdrantException {
        val message = description.orEmpty()
        return when (status.code) {
            Status.Code.NOT_FOUND -> notFound(collection, message)
            Status.Code.UNAUTHENTICATED -> KdrantException.Unauthorized(message.ifBlank { "Unauthorized" })
            // The gRPC counterpart of HTTP 403: the credential is understood and does not reach this
            // far. A scoped token refused on a write raises the same type over either engine — and so
            // does a node refusing writes, which is a different failure wearing the same code.
            Status.Code.PERMISSION_DENIED -> if (namesReadOnly(message)) {
                KdrantException.ReadOnly(collection, message)
            } else {
                KdrantException.Forbidden(collection, message)
            }
            Status.Code.INVALID_ARGUMENT -> when {
                collection != null && looksLikeMissingCollection(message) -> notFound(collection, message)
                namesUnavailableShard(message) -> KdrantException.ShardUnavailable(collection, message)
                namesReadOnly(message) -> KdrantException.ReadOnly(collection, message)
                else -> KdrantException.InvalidRequest(message.ifBlank { "Qdrant rejected the request" })
            }
            Status.Code.ALREADY_EXISTS -> KdrantException.AlreadyExists(message.ifBlank { "Already exists" })
            Status.Code.DEADLINE_EXCEEDED -> KdrantException.Timeout(message.ifBlank { "Deadline exceeded" }, cause)
            Status.Code.RESOURCE_EXHAUSTED -> KdrantException.RateLimited(message = message.ifBlank { RATE_LIMITED })
            Status.Code.UNAVAILABLE -> if (namesUnavailableShard(message)) {
                KdrantException.ShardUnavailable(collection, message)
            } else {
                KdrantException.ServiceUnavailable(message.ifBlank { UNAVAILABLE })
            }
            Status.Code.UNIMPLEMENTED -> KdrantException.InvalidRequest(
                "Qdrant does not implement this call over gRPC${if (message.isBlank()) "" else ": $message"}",
            )
            else -> if (namesUnavailableShard(message)) {
                KdrantException.ShardUnavailable(collection, message)
            } else {
                KdrantException.ServerError(message.ifBlank { "Qdrant returned ${status.code}" })
            }
        }
    }

    private fun notFound(collection: String?, message: String): KdrantException =
        if (collection != null) {
            KdrantException.CollectionNotFound(collection, message)
        } else {
            KdrantException.InvalidRequest(message.ifBlank { "Not found" })
        }

    /**
     * Qdrant answers some write paths on a missing collection with `INVALID_ARGUMENT` rather than
     * `NOT_FOUND`, and the only thing separating that from a genuinely malformed request is the text.
     * Matching on it is unpleasant, and the alternative is reporting a missing collection as a bad
     * request on exactly the operations where the caller most needs to tell them apart.
     */
    private fun looksLikeMissingCollection(message: String): Boolean =
        message.contains("doesn't exist", ignoreCase = true) ||
            message.contains("does not exist", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true)

    /**
     * The same two message tests the REST engine applies, for the same reason: a degraded cluster
     * answers with a generic code and says what really happened in the text. Stated here rather than
     * shared from `kdrant-transport-rest`, because a gRPC engine that depended on the REST one to read
     * an error would be a dependency in the wrong direction.
     */
    private fun namesReadOnly(message: String): Boolean {
        val text = message.lowercase()
        if ("read-only" in text || "read only" in text || "readonly" in text) return true
        val pressure = "disk usage" in text || "resident memory" in text || "memory usage" in text
        return pressure && ("exceed" in text || "limit" in text || "above" in text || "too high" in text)
    }

    private fun namesUnavailableShard(message: String): Boolean {
        val text = message.lowercase()
        val unreachable = listOf(
            "not available", "unavailable", "no active", "not enough", "no replica",
            "dead", "is down", "failed to", "cannot",
        ).any { it in text }
        if (("shard" in text || "replica" in text) && unreachable) return true

        // The fan-out form: some of the peers a request had to reach did not answer, and the message
        // names the transport failure rather than the shard.
        val fanOut = "operations failed" in text || "operation failed" in text
        val transport = listOf("unavailable", "dns", "name resolution", "connect", "transport", "timed out")
            .any { it in text }
        return fanOut && transport
    }

    private const val RATE_LIMITED = "Rate limited by Qdrant"
    private const val UNAVAILABLE = "Qdrant is temporarily unavailable"
}
