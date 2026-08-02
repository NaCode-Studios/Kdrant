package dev.kdrant.migrate

/**
 * What a migration did, and what it checked before it was willing to move an alias.
 *
 * @property source the collection read from.
 * @property target the collection written to.
 * @property copied points written by this run. A resumed run counts only what it wrote itself; add
 *   [resumedFrom]'s `copied` for the total across runs.
 * @property resumed whether this run picked up a checkpoint rather than starting from the first point.
 * @property sourceCount points in the source when the copy finished.
 * @property targetCount points in the target when the copy finished.
 * @property sampledPairs how many points the neighbour check could compare. Zero when the caller
 *   turned the sample off.
 * @property recall the mean overlap between the source's neighbours and the target's, over
 *   [sampledPairs] queries. `null` when nothing was sampled.
 * @property aliasMoved whether the alias now points at [target].
 */
public class MigrationReport(
    public val source: String,
    public val target: String,
    public val copied: Long,
    public val resumed: Boolean,
    public val sourceCount: Long,
    public val targetCount: Long,
    public val sampledPairs: Int,
    public val recall: Double?,
    public val aliasMoved: Boolean,
) {
    override fun toString(): String =
        "MigrationReport(source=$source, target=$target, copied=$copied, resumed=$resumed, " +
            "sourceCount=$sourceCount, targetCount=$targetCount, sampledPairs=$sampledPairs, " +
            "recall=$recall, aliasMoved=$aliasMoved)"
}

/**
 * The verification refused the copy, so the alias was left where it was and the target is still there
 * to inspect.
 *
 * This exception is the point of the module. A tool that swaps because the copy finished without
 * throwing is a tool that will one day point production at an empty collection, so a failed check has
 * to be loud rather than a field on a report nobody reads.
 *
 * @property report the state at the moment the check failed.
 */
public class MigrationVerificationFailed(
    message: String,
    public val report: MigrationReport,
) : Exception(message)

/**
 * How hard to check the copy before the alias is allowed to move.
 *
 * Counts are always compared. The neighbour sample is what catches the failure counts cannot see: a
 * copy where every point arrived and the new embedding ranks them differently enough that search is
 * no longer the search anyone tested.
 *
 * @property sampleSize how many source points to re-query through both collections. `0` turns the
 *   neighbour check off and leaves only the count comparison, which is the right setting for a
 *   collection whose vectors the check cannot read (sparse or multi-vector) and the wrong one
 *   everywhere else.
 * @property topK neighbours per query to compare.
 * @property minRecall the mean overlap that has to be reached, in `0.0..1.0`. `1.0` demands the two
 *   collections agree exactly, which is right for a copy that does not re-embed and unreachable for
 *   one that does.
 * @property using which named vector to query with; `null` for a collection with a single unnamed
 *   vector.
 */
public class MigrationVerification(
    public val sampleSize: Int = 32,
    public val topK: Int = 10,
    public val minRecall: Double = 0.9,
    public val using: String? = null,
) {
    init {
        require(sampleSize >= 0) { "sampleSize must be >= 0, was $sampleSize" }
        require(topK > 0) { "topK must be > 0, was $topK" }
        require(minRecall in 0.0..1.0) { "minRecall must be in 0.0..1.0, was $minRecall" }
    }
}
