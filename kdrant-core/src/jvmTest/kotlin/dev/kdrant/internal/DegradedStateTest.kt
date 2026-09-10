@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.internal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phrasings a degraded Qdrant uses, asserted once.
 *
 * They used to be asserted in the REST module against a table the gRPC engine carried its own copy of,
 * and the copies diverged: one learned `timed out` and not `timeout`, so a downed shard read as an
 * ordinary server error and told the caller not to retry a state that clears in seconds. There is one
 * implementation now and one table, and adding a phrasing here changes both engines without either being
 * edited.
 *
 * Each engine keeps its own test for what it does with the answer, because the status codes differ: REST
 * sees a 4xx or a 5xx and gRPC sees a `Status.Code`.
 */
class DegradedStateTest {

    @Test
    fun `the phrasings a degraded cluster uses are read as an unavailable shard`() {
        listOf(
            "Not enough replicas of shard 1 are available",
            "No replica available for shard 1",
            "Shard 1 has no active replicas",
            "Service internal error: shard 1 is dead",
            "Failed to read from shard 1",
            "Cannot resolve replica for shard 0",
            // Verbatim from a CI run against a two-node cluster with the second node stopped. It names no
            // shard at all, which is why a matcher keyed only on the word read a dead node as a generic
            // server error.
            "Service internal error: 1 of 1 read operations failed: Service internal error: Tonic " +
                "status error: code: 'The service is currently unavailable', message: 'dns error'",
            // Also verbatim, and the one that cost a release: the list knew "timed out" and not "timeout".
            "Service internal error: 1 of 1 read operations failed: Timeout error: Deadline Exceeded: " +
                "code: 'Deadline expired before operation could complete', message: " +
                "\"Healthcheck timeout 2000ms exceeded\"",
        ).forEach { message ->
            assertTrue(
                DegradedState.namesUnavailableShard(message),
                "'$message' should have been read as an unavailable shard",
            )
        }
    }

    /**
     * Both shapes need two halves, so an ordinary failure that happens to say "failed" is not turned into
     * a cluster diagnosis. An over-eager matcher is worse than a blind one here: it tells a caller to
     * retry something that will fail the same way every time.
     */
    @Test
    fun `an ordinary failure is not read as a degraded cluster`() {
        listOf(
            "Service internal error: failed to flush",
            "Wrong input: shard key 'eu-west' is not a valid key",
            "Service internal error: 1 of 1 read operations failed: index out of bounds",
            "Not found: collection docs does not exist",
            null,
        ).forEach { message ->
            assertFalse(
                DegradedState.namesUnavailableShard(message),
                "'$message' is not a degraded cluster and must not be read as one",
            )
        }
    }

    @Test
    fun `the node refusing writes is recognised however it is worded`() {
        listOf(
            "Service is in read-only mode",
            "The node is read only",
            "readonly: writes are refused",
            "Disk usage exceeded the configured limit",
            "Resident memory is above the strict mode limit",
            "Memory usage too high",
        ).forEach { message ->
            assertTrue(
                DegradedState.namesReadOnly(message),
                "'$message' should have been read as the node refusing writes",
            )
        }
    }

    /**
     * The credential being refused is the failure this one has to be told apart from, because Qdrant
     * answers 403 for both and the two need opposite responses: wait, or fix the token.
     */
    @Test
    fun `a refused credential is not the node refusing writes`() {
        listOf(
            "Must provide an API key or an Authorization bearer token",
            "Invalid api key",
            "Forbidden",
            null,
        ).forEach { message ->
            assertFalse(
                DegradedState.namesReadOnly(message),
                "'$message' is not the node refusing writes",
            )
        }
    }
}
