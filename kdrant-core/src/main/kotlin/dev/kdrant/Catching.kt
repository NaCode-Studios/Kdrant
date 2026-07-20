package dev.kdrant

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and captures its outcome in a [Result], **re-throwing [CancellationException]** so coroutine
 * cancellation is never swallowed — unlike the standard-library `runCatching`, which would trap it.
 *
 * The exception-based API stays the primary style; reach for this only where a `Result` reads better than a
 * `try` / `catch` at the call site.
 *
 * ```kotlin
 * val hits = catching { qdrant.search("docs") { query(vector) } }
 *     .getOrElse { emptyList() }
 * ```
 */
public suspend fun <T> catching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
