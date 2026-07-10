package dev.kdrant

/**
 * Base type for all errors surfaced by a Kdrant client. A sealed hierarchy lets callers
 * exhaustively `when` over the failure modes.
 *
 * Note: [kotlinx.coroutines.CancellationException] is never wrapped in a [KdrantException];
 * it must always propagate so structured concurrency and cancellation keep working.
 */
public sealed class KdrantException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The referenced collection does not exist. */
    public class CollectionNotFound(
        public val collection: String,
    ) : KdrantException("Collection not found: $collection")

    /** Authentication failed or is required (missing/invalid API key). */
    public class Unauthorized(
        message: String = "Unauthorized",
    ) : KdrantException(message)

    /** The server rejected the request as malformed (HTTP 4xx other than auth/not-found). */
    public class InvalidRequest(
        message: String,
    ) : KdrantException(message)

    /** The request exceeded its configured timeout. */
    public class Timeout(
        message: String,
        cause: Throwable? = null,
    ) : KdrantException(message, cause)

    /** A transport/connection-level failure, or an unexpected server (5xx) error. */
    public class Transport(
        message: String,
        cause: Throwable? = null,
    ) : KdrantException(message, cause)
}
