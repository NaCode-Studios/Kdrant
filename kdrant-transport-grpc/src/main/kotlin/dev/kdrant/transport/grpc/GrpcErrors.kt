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
            // far. A scoped token refused on a write raises the same type over either engine.
            Status.Code.PERMISSION_DENIED -> KdrantException.Forbidden(collection, message)
            Status.Code.INVALID_ARGUMENT ->
                if (collection != null && looksLikeMissingCollection(message)) {
                    notFound(collection, message)
                } else {
                    KdrantException.InvalidRequest(message.ifBlank { "Qdrant rejected the request" })
                }
            Status.Code.ALREADY_EXISTS -> KdrantException.AlreadyExists(message.ifBlank { "Already exists" })
            Status.Code.DEADLINE_EXCEEDED -> KdrantException.Timeout(message.ifBlank { "Deadline exceeded" }, cause)
            Status.Code.RESOURCE_EXHAUSTED -> KdrantException.RateLimited(message = message.ifBlank { RATE_LIMITED })
            Status.Code.UNAVAILABLE -> KdrantException.ServiceUnavailable(message.ifBlank { UNAVAILABLE })
            Status.Code.UNIMPLEMENTED -> KdrantException.InvalidRequest(
                "Qdrant does not implement this call over gRPC${if (message.isBlank()) "" else ": $message"}",
            )
            else -> KdrantException.ServerError(message.ifBlank { "Qdrant returned ${status.code}" })
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

    private const val RATE_LIMITED = "Rate limited by Qdrant"
    private const val UNAVAILABLE = "Qdrant is temporarily unavailable"
}
