package dev.kdrant.internal

/**
 * Which degraded cluster state a Qdrant error message describes.
 *
 * These decide the `retryable` flag on the exception a caller receives, and both engines have to agree
 * about them. They were a copy in each engine until the copies diverged: the fan-out matcher learned
 * `timed out` on one side and not `timeout`, so
 * `1 of 1 read operations failed: Timeout error: Deadline Exceeded ... "Healthcheck timeout 2000ms
 * exceeded"` read as an ordinary server error, and a condition that clears in seconds told the caller not
 * to retry. One copy cannot disagree with itself.
 *
 * They live here rather than in an engine because what they decide lives here: `KdrantException` and its
 * `retryable` property. They are `public` only because Kotlin has no cross-module `internal`.
 *
 * Matched on the message because the status code does not separate these states. Qdrant answers 403 both
 * when a token may not write and when the node may not, and it answers a downed shard on either side of
 * the 4xx/5xx line depending on which check refused first. There is no machine-readable error code to
 * key on.
 */
public object DegradedState {

    /**
     * Whether the node is refusing writes while still serving reads.
     *
     * Two wordings mean the same thing to a caller: the explicit read-only state, and a strict-mode limit
     * on disk or memory, which is the same event with the cause named.
     *
     * Deliberately substrings rather than exact strings, so the wording may change without turning a
     * read-only node back into an auth failure. When nothing matches, the mapping falls back to what
     * these failures were before the state was modelled, so an unrecognised message costs the caller
     * nothing they had.
     */
    @InternalKdrantApi
    public fun namesReadOnly(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        if ("read-only" in text || "read only" in text || "readonly" in text) return true
        // Strict mode's disk and memory ceilings: writes refused, reads still served.
        val pressure = "disk usage" in text || "resident memory" in text || "memory usage" in text
        return pressure && ("exceed" in text || "limit" in text || "above" in text || "too high" in text)
    }

    /**
     * Whether a failure is the cluster being unreachable rather than a bad request or a broken server.
     *
     * Two shapes, because Qdrant reports it two ways. Sometimes it names the shard or the replica. More
     * often, when a peer is simply gone, it reports the fan-out: "1 of 1 read operations failed", with
     * the reason underneath. Nothing in that second message contains the word shard, which is why a
     * matcher keyed only on it read a dead node as a generic server error.
     *
     * Both shapes require two halves, so an ordinary failure that happens to say "failed" is not read as
     * a cluster diagnosis.
     */
    @InternalKdrantApi
    public fun namesUnavailableShard(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        val unreachable = listOf(
            "not available", "unavailable", "no active", "not enough", "no replica",
            "dead", "is down", "failed to", "cannot",
        ).any { it in text }
        if (("shard" in text || "replica" in text) && unreachable) return true

        // The fan-out form. Qdrant words the reason several ways, and "timeout" and "deadline" are the two
        // that cost a release: the list had "timed out" and not "timeout", so a downed shard read as an
        // ordinary server error and therefore as terminal. A transient cluster state reported as not
        // retryable is the one classification mistake that changes what somebody does.
        val fanOut = "operations failed" in text || "operation failed" in text
        val transport = listOf(
            "unavailable", "dns", "name resolution", "connect", "transport",
            "timed out", "timeout", "deadline",
        ).any { it in text }
        return fanOut && transport
    }
}
