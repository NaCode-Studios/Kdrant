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

    /**
     * Whether the identical request could succeed later without being changed.
     *
     * `true` says the failure is about the cluster's current state — it is rate limiting, a node is
     * out of disk, a shard has no live replica — and waiting is a strategy. `false` says the request
     * itself is the problem, and the same bytes will be refused again however long you wait.
     *
     * It is a statement about the *server*, not about your data. Retrying a **read** on a retryable
     * failure is always safe. Retrying a **write** is safe only where the write is idempotent: an
     * upsert keyed by point id is, because writing the same point twice leaves the same collection;
     * a [dev.kdrant.QdrantClient.batchUpdate] mixing upserts and filter-scoped deletes is not, because
     * the delete's filter is re-evaluated against whatever the first attempt already applied.
     *
     * The client already retries the retryable failures it can see, with backoff, before surfacing
     * them. Reaching a caller means those attempts were used up.
     */
    public open val retryable: Boolean get() = false

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

    /**
     * Authentication failed or is required: no credential, or one the server does not accept
     * (HTTP 401). [Forbidden] is the narrower case where the credential is valid.
     */
    public open class Unauthorized(
        message: String = "Unauthorized",
    ) : KdrantException(message)

    /**
     * The credential is valid and does not reach this far (HTTP 403): a read-only JWT asked to write,
     * or a token scoped to other collections. A subclass of [Unauthorized] so an existing
     * `catch (e: KdrantException.Unauthorized)` keeps catching it, and a `when` over the sealed
     * hierarchy stays exhaustive.
     *
     * This is the failure worth acting on rather than retrying: the same request will be refused
     * again with the same token, however long you wait.
     *
     * @property collection the collection the refused operation named, when it named one.
     */
    public open class Forbidden internal constructor(
        public val collection: String?,
        serverMessage: String?,
        prefix: String,
    ) : Unauthorized(
        buildString {
            append(prefix)
            if (collection != null) append(" on collection '").append(collection).append("'")
            if (!serverMessage.isNullOrBlank()) append(" (").append(serverMessage).append(")")
        },
    ) {
        public constructor(collection: String? = null, serverMessage: String? = null) : this(
            collection,
            serverMessage,
            "Forbidden: the credential in use is not allowed to perform this operation",
        )
    }

    /**
     * The node is refusing writes while still serving reads (HTTP 403, read-only).
     *
     * A subclass of [Forbidden], and therefore of [Unauthorized], so an existing `catch` keeps
     * catching it and the sealed `when` stays exhaustive. That inheritance is a compatibility
     * decision rather than a description: nothing is wrong with the credential. Qdrant answers a
     * write with 403 both when the token may not write and when the *node* may not write, and until
     * this class existed those two arrived as the same exception, which is the difference between
     * fixing a token and adding a disk.
     *
     * Retryable, unlike its parent: the node comes back when the condition that made it read-only is
     * cleared. Reads against the same node keep working throughout, which is what makes this worth
     * telling apart at all — a caller can degrade to read-only rather than stop.
     */
    public class ReadOnly(
        collection: String? = null,
        serverMessage: String? = null,
    ) : Forbidden(collection, serverMessage, "Read-only: the node is not accepting writes") {
        override val retryable: Boolean get() = true
    }

    /**
     * The request needed a shard whose replicas are not available at the consistency it asked for.
     *
     * Distinct from [ServiceUnavailable], which is the whole node saying "not now", and from
     * [CollectionNotFound], which is the collection not existing. Here the collection exists and part
     * of it is unreachable, so the answer would be incomplete rather than absent — which is why
     * Qdrant refuses instead of returning a partial result, and why lowering the read consistency can
     * make the same request succeed.
     *
     * @property collection the collection whose shard was unavailable, when the request named one.
     */
    public class ShardUnavailable(
        public val collection: String? = null,
        serverMessage: String? = null,
    ) : KdrantException(
        buildString {
            append("A shard is not available")
            if (collection != null) append(" for collection '").append(collection).append("'")
            append(": not enough replicas are alive to answer at the requested consistency")
            if (!serverMessage.isNullOrBlank()) append(" (").append(serverMessage).append(")")
        },
    ) {
        override val retryable: Boolean get() = true
    }

    /**
     * A call that the client had to send as several requests failed after some of them were applied.
     *
     * The client splits a large upsert to stay under Qdrant's payload cap, so one `upsert` can be
     * several writes. When a later one fails, the earlier ones are already in the collection, and
     * before this existed the caller was told only that the call failed — leaving them to choose
     * between re-sending everything and losing the rest, with nothing to base the choice on.
     *
     * Upsert is idempotent per point id, so re-sending the whole call is safe and costs the writes
     * that already landed. [applied] is what makes the cheaper choice possible.
     *
     * There is no total here, deliberately. The count of points a call was given is known for a list
     * and unknowable for a `Flow`, which is drained as it is sent, and a number that means one thing on
     * one overload and something else on the other is worse than the number the caller already has.
     *
     * @property applied how many points the server acknowledged before the failure.
     * @property cause the failure that ended it, which is what says whether retrying is worth it.
     */
    public class PartiallyApplied(
        public val applied: Int,
        cause: KdrantException,
    ) : KdrantException(
        "Partially applied: $applied points were written before the call failed (${cause.message}). " +
            "Upsert is idempotent per point id, so re-sending the whole call is safe.",
        cause,
    ) {
        /** Whatever ended it decides. A partial write from a rate limit is worth retrying; a malformed point is not. */
        override val retryable: Boolean get() = (cause as KdrantException).retryable
    }

    /** The server rejected the request as malformed (HTTP 4xx other than auth/not-found). */
    public class InvalidRequest(
        message: String,
    ) : KdrantException(message)

    /** The request exceeded its configured timeout. */
    public class Timeout(
        message: String,
        cause: Throwable? = null,
    ) : KdrantException(message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * A transport/connection-level failure — Qdrant could not be reached (connection refused, DNS
     * failure, connection reset, ...). Transient I/O failures are retried with backoff before this
     * is surfaced.
     */
    public class Transport(
        message: String,
        cause: Throwable? = null,
    ) : KdrantException(message, cause) {
        override val retryable: Boolean get() = true
    }

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
    ) : KdrantException(message) {
        override val retryable: Boolean get() = true
    }

    /**
     * Qdrant is temporarily unavailable (HTTP 503, e.g. a shard is not ready). Retryable: surfaced
     * only after the client's retries were exhausted.
     */
    public class ServiceUnavailable(
        message: String = "Qdrant is temporarily unavailable (HTTP 503)",
    ) : KdrantException(message) {
        override val retryable: Boolean get() = true
    }

    /** An unexpected server-side error (HTTP 5xx other than 503). Not retried automatically. */
    public class ServerError(
        message: String,
    ) : KdrantException(message)
}
