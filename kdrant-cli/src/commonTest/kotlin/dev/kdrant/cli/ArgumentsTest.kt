package dev.kdrant.cli

import dev.kdrant.TrustAnchors
import dev.kdrant.migrate.MigrationCheckpoint
import dev.kdrant.model.PointId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgumentsTest {

    private fun parse(vararg argv: String, env: Map<String, String> = emptyMap()) =
        Arguments.parse(argv.toList(), env::get)

    @Test
    fun `the connection defaults suit somebody running this against a local node`() {
        val arguments = parse("collections")

        assertEquals("localhost", arguments.host)
        assertEquals(6333, arguments.port)
        assertNull(arguments.apiKey)
        assertEquals(false, arguments.useTls)
    }

    @Test
    fun `both spellings of an option work because both are what people type`() {
        assertEquals("db.example.com", parse("collections", "--host", "db.example.com").host)
        assertEquals("db.example.com", parse("collections", "--host=db.example.com").host)
    }

    @Test
    fun `the environment supplies what the flags leave out`() {
        val arguments = parse(
            "collections",
            env = mapOf("QDRANT_HOST" to "cloud", "QDRANT_PORT" to "6334", "QDRANT_API_KEY" to "secret"),
        )

        assertEquals("cloud", arguments.host)
        assertEquals(6334, arguments.port)
        assertEquals("secret", arguments.apiKey)
    }

    @Test
    fun `a flag on the command line beats the environment`() {
        val arguments = parse("collections", "--host", "explicit", env = mapOf("QDRANT_HOST" to "from-env"))

        assertEquals("explicit", arguments.host)
    }

    @Test
    fun `a valueless flag does not swallow the word after it`() {
        val arguments = parse("snapshot", "--tls", "create", "docs")

        assertEquals(true, arguments.useTls)
        assertEquals(listOf("snapshot", "create", "docs"), arguments.positional)
    }

    @Test
    fun `a number that is not one is refused where the user can still fix it`() {
        val failure = assertFailsWith<CliFailure> { parse("scroll", "docs", "--limit", "lots").intOption("limit") }

        assertTrue(failure.message!!.contains("--limit"))
    }

    @Test
    fun `a missing positional names what was expected rather than crashing`() {
        val failure = assertFailsWith<CliFailure> { parse("scroll").require(1, "a collection name") }

        assertTrue(failure.message!!.contains("a collection name"))
    }

    @Test
    fun `a CA file turns into a PEM trust decision and its absence leaves the system store`() {
        assertEquals(TrustAnchors.System, parse("collections").trustAnchors { error("not read") })

        val anchors = parse("collections", "--ca-file", "ca.pem").trustAnchors { "-----BEGIN CERTIFICATE-----\nx\n" }

        assertTrue(anchors is TrustAnchors.Pem)
    }

    @Test
    fun `a checkpoint round-trips through a file and keeps the kind of its id`() = runTest {
        val files = InMemoryFiles()
        val store = FileCheckpointStore(files, "checkpoint")

        store.save("id", MigrationCheckpoint(PointId.num(42), copied = 100))
        assertEquals(PointId.num(42), store.load("id")?.resumeAt)
        assertEquals(100L, store.load("id")?.copied)

        store.save("id", MigrationCheckpoint(PointId.uuid(UUID), copied = 7))
        assertEquals(PointId.uuid(UUID), store.load("id")?.resumeAt)

        store.clear("id")
        assertNull(store.load("id"))
    }

    @Test
    fun `a corrupt checkpoint resumes from the start rather than from nonsense`() = runTest {
        val files = InMemoryFiles()
        val store = FileCheckpointStore(files, "checkpoint")

        assertNull(store.load("id"), "a checkpoint that is not there is not a failure")

        files.writeText("checkpoint", "garbage")
        assertNull(store.load("id"))

        files.writeText("checkpoint", "num:not-a-number:0")
        assertNull(store.load("id"))
    }

    private class InMemoryFiles : Files {
        private val contents = mutableMapOf<String, String>()

        override fun read(path: String): String? = contents[path]

        override fun writeText(path: String, text: String) {
            contents[path] = text
        }

        override fun delete(path: String) {
            contents.remove(path)
        }

        override suspend fun write(path: String, body: suspend ((ByteArray) -> Unit) -> Unit) {
            val bytes = mutableListOf<Byte>()
            body { chunk -> bytes.addAll(chunk.toList()) }
            contents[path] = bytes.toByteArray().decodeToString()
        }
    }

    private companion object {
        const val UUID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
