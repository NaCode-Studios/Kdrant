package dev.kdrant.migrate

import dev.kdrant.model.PointId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A checkpoint store that survives the process, which is the only kind that makes resuming real.
 *
 * One small file per migration under [directory]. It is written to a temporary file and moved into
 * place, so a process killed mid-write leaves the previous checkpoint intact rather than a truncated
 * one — the failure this store exists to handle is the process dying, and dying during the write is
 * not a special case.
 *
 * A file is the right default because a migration is usually a job with a volume attached and no
 * database of its own. Anything with somewhere better to put a cursor should implement
 * [MigrationCheckpointStore] against that instead.
 */
public class FileMigrationCheckpointStore(
    private val directory: Path,
) : MigrationCheckpointStore {

    override suspend fun load(id: String): MigrationCheckpoint? {
        val file = fileFor(id)
        if (!file.exists()) return null
        val parts = file.readText().trim().split(SEPARATOR, limit = 3)
        if (parts.size != 3) return null
        val copied = parts[2].toLongOrNull() ?: return null
        val resumeAt = when (parts[0]) {
            NUM -> parts[1].toULongOrNull()?.let { PointId.num(it) }
            UUID -> PointId.uuid(parts[1])
            else -> null
        } ?: return null
        return MigrationCheckpoint(resumeAt, copied)
    }

    override suspend fun save(id: String, checkpoint: MigrationCheckpoint) {
        directory.createDirectories()
        val encoded = when (val at = checkpoint.resumeAt) {
            is PointId.Num -> "$NUM$SEPARATOR${at.value}"
            is PointId.Uuid -> "$UUID$SEPARATOR${at.value}"
        }
        val temporary = directory.resolve("${safeName(id)}$TEMPORARY_SUFFIX")
        temporary.writeText("$encoded$SEPARATOR${checkpoint.copied}")
        Files.move(temporary, fileFor(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun clear(id: String) {
        fileFor(id).deleteIfExists()
    }

    private fun fileFor(id: String): Path = directory.resolve("${safeName(id)}$SUFFIX")

    /** A migration id names two collections, so it can hold anything a collection name can. */
    private fun safeName(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }
        .joinToString("")

    private companion object {
        const val SUFFIX = ".checkpoint"
        const val TEMPORARY_SUFFIX = ".checkpoint.tmp"
        const val SEPARATOR = ":"
        const val NUM = "num"
        const val UUID = "uuid"
    }
}
