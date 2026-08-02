package dev.kdrant.migrate

import dev.kdrant.model.PointId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

class FileMigrationCheckpointStoreTest {

    @Test
    fun `a numeric cursor survives the round trip`(@TempDir directory: Path) = runTest {
        val store = FileMigrationCheckpointStore(directory)

        store.save("docs-768->docs-1536", MigrationCheckpoint(PointId.num(41_337UL), copied = 41_337))
        val loaded = store.load("docs-768->docs-1536")

        assertEquals(PointId.num(41_337UL), loaded?.resumeAt)
        assertEquals(41_337L, loaded?.copied)
    }

    @Test
    fun `a uuid cursor survives the round trip`(@TempDir directory: Path) = runTest {
        val store = FileMigrationCheckpointStore(directory)
        val id = PointId.uuid("550e8400-e29b-41d4-a716-446655440000")

        store.save("m", MigrationCheckpoint(id, copied = 1))

        assertEquals(id, store.load("m")?.resumeAt)
    }

    @Test
    fun `a migration that has never run has no checkpoint`(@TempDir directory: Path) = runTest {
        assertNull(FileMigrationCheckpointStore(directory).load("never-run"))
    }

    @Test
    fun `saving twice leaves one file, and clearing leaves none`(@TempDir directory: Path) = runTest {
        val store = FileMigrationCheckpointStore(directory)

        store.save("m", MigrationCheckpoint(PointId.num(1UL), 1))
        store.save("m", MigrationCheckpoint(PointId.num(2UL), 2))

        assertEquals(1, directory.listDirectoryEntries().size, "the temporary file should not survive the move")
        assertEquals(PointId.num(2UL), store.load("m")?.resumeAt)

        store.clear("m")
        assertEquals(0, directory.listDirectoryEntries().size)
        assertNull(store.load("m"))
    }

    @Test
    fun `two migrations do not share a file`(@TempDir directory: Path) = runTest {
        val store = FileMigrationCheckpointStore(directory)

        store.save("a->b", MigrationCheckpoint(PointId.num(1UL), 1))
        store.save("c->d", MigrationCheckpoint(PointId.num(2UL), 2))

        assertEquals(PointId.num(1UL), store.load("a->b")?.resumeAt)
        assertEquals(PointId.num(2UL), store.load("c->d")?.resumeAt)
    }
}
