package dev.kdrant.cli

import dev.kdrant.migrate.MigrationCheckpoint
import dev.kdrant.migrate.MigrationCheckpointStore
import dev.kdrant.model.PointId

/**
 * The filesystem, as three operations, so everything above this line is testable without one.
 *
 * `kdrant-migrate` ships a file-backed checkpoint store for the JVM and nothing for native, which is
 * the half of it a CLI needs. Rather than pushing a `kotlinx-io` dependency into a published module
 * for one tool's sake, the tool carries its own.
 */
internal interface Files {
    fun read(path: String): String?
    fun writeText(path: String, text: String)
    fun delete(path: String)

    /** Streams to [path], handing the writer a sink so a multi-gigabyte snapshot is never buffered. */
    suspend fun write(path: String, body: suspend ((ByteArray) -> Unit) -> Unit)
}

/**
 * A migration checkpoint in a file, which is what makes an interrupted `kdrant migrate` resumable
 * across process restarts rather than only across retries.
 *
 * The format is one line: `kind:id:copied`. A point id is a number or a UUID and Qdrant treats `1` and
 * `"1"` as different points, so the kind has to survive the round trip — a file holding only `1` would
 * resume a numeric migration correctly and a UUID one at the wrong place.
 */
internal class FileCheckpointStore(
    private val files: Files,
    private val path: String,
) : MigrationCheckpointStore {

    override suspend fun load(id: String): MigrationCheckpoint? {
        val text = files.read(path)?.trim()?.ifBlank { null } ?: return null
        val parts = text.split(':', limit = 3)
        if (parts.size != 3) return null
        val (kind, value, copied) = parts
        val resumeAt = when (kind) {
            "num" -> value.toULongOrNull()?.let { PointId.num(it) } ?: return null
            "uuid" -> PointId.uuid(value)
            else -> return null
        }
        return MigrationCheckpoint(resumeAt, copied.toLongOrNull() ?: 0L)
    }

    override suspend fun save(id: String, checkpoint: MigrationCheckpoint) {
        val line = when (val resumeAt = checkpoint.resumeAt) {
            is PointId.Num -> "num:${resumeAt.value}:${checkpoint.copied}"
            is PointId.Uuid -> "uuid:${resumeAt.value}:${checkpoint.copied}"
        }
        files.writeText(path, line)
    }

    override suspend fun clear(id: String) {
        files.delete(path)
    }
}
