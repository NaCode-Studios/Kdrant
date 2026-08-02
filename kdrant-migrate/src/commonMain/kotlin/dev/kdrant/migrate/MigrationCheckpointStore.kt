package dev.kdrant.migrate

import dev.kdrant.model.PointId

/**
 * Where a migration remembers how far it got.
 *
 * A copy of ten million points will be interrupted at least once, and starting over is not an answer.
 * After every batch that Qdrant has acknowledged, the migration writes the id it reached here; on the
 * next run it reads that id and resumes from it.
 *
 * The default is [inMemory], which survives nothing and is right for a test. Anything that has to
 * survive the process needs a durable one: `FileMigrationCheckpointStore` on the JVM, or your own —
 * a row in whatever database the job already writes to is usually the least new machinery.
 *
 * Implementations are called from one coroutine at a time and need no locking of their own.
 */
public interface MigrationCheckpointStore {

    /** The checkpoint stored under [id], or `null` if this migration has not run before. */
    public suspend fun load(id: String): MigrationCheckpoint?

    /** Records how far the migration [id] has got. Called once per acknowledged batch. */
    public suspend fun save(id: String, checkpoint: MigrationCheckpoint)

    /** Forgets [id], so the next run starts from the beginning. Called when a migration completes. */
    public suspend fun clear(id: String)

    public companion object {
        /**
         * A store that lives as long as the process does. Enough to prove the resume path in a test,
         * and enough for a migration small enough that restarting it costs nothing.
         */
        public fun inMemory(): MigrationCheckpointStore = InMemoryCheckpointStore()
    }
}

/**
 * How far a migration got: the point id to resume at and how many points had been written by then.
 *
 * [resumeAt] is inclusive, which is Qdrant's cursor semantics rather than a choice made here. Resuming
 * re-reads and re-writes that one point, and an upsert keyed by point id makes that a repeat rather
 * than a duplicate.
 *
 * @property copied points written before this checkpoint was taken, for reporting rather than for
 *   control flow — the copy is driven by [resumeAt].
 */
public class MigrationCheckpoint(
    public val resumeAt: PointId,
    public val copied: Long,
) {
    override fun toString(): String = "MigrationCheckpoint(resumeAt=$resumeAt, copied=$copied)"
}

private class InMemoryCheckpointStore : MigrationCheckpointStore {
    private val checkpoints = mutableMapOf<String, MigrationCheckpoint>()

    override suspend fun load(id: String): MigrationCheckpoint? = checkpoints[id]

    override suspend fun save(id: String, checkpoint: MigrationCheckpoint) {
        checkpoints[id] = checkpoint
    }

    override suspend fun clear(id: String) {
        checkpoints.remove(id)
    }
}
