package dev.kdrant

import kotlin.time.Duration

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
        serverMessage: String? = null,
    ) : KdrantException(
        if (serverMessage.isNullOrBlank()) {
            "Collection not found: $collection"
        } else {
            "Collection not found: $collection ($serverMessage)"
        },
    )

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

    /**
     * A transport/connection-level failure — Qdrant could not be reached (connection refused, DNS
     * failure, connection reset, ...). Transient I/O failures are retried with backoff before this
     * is surfaced.
     */
    public class Transport(
        message: String,
        cause: Throwable? = null,
    ) : KdrantException(message, cause)

    /** The collection or resource already exists (HTTP 409). Not retryable. */
    public class AlreadyExists(
        message: String,
    ) : KdrantException(message)

    /**
     * The server rejected the request because it is rate-limited (HTTP 429). Retryable: the client
     * already retried with backoff, honoring the server's `Retry-After`, before surfacing this.
     *
     * @property retryAfter the server's `Retry-After` hint, if it sent one.
     */
    public class RateLimited(
        public val retryAfter: Duration? = null,
        message: String = "Rate limited by Qdrant (HTTP 429)",
    ) : KdrantException(message)

    /**
     * Qdrant is temporarily unavailable (HTTP 503, e.g. a shard is not ready). Retryable: surfaced
     * only after the client's retries were exhausted.
     */
    public class ServiceUnavailable(
        message: String = "Qdrant is temporarily unavailable (HTTP 503)",
    ) : KdrantException(message)

    /** An unexpected server-side error (HTTP 5xx other than 503). Not retried automatically. */
    public class ServerError(
        message: String,
    ) : KdrantException(message)
}
