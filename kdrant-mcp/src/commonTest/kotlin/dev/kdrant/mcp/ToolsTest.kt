package dev.kdrant.mcp

import dev.kdrant.QdrantClient
import dev.kdrant.transport.rest.Kdrant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tool surface, without a transport or a server.
 *
 * What a real handshake proves is that the protocol works, and `prove-mcp.sh` does that in CI against a
 * running Qdrant. What it cannot cheaply prove is the part a model actually trips over: whether a write
 * tool is offered when it should not be, and whether a bad argument comes back as a sentence it can act
 * on rather than as a type name.
 */
class ToolsTest {

    private fun names(allowWrites: Boolean): List<String> =
        Tools.definitions(allowWrites).map { it.name }

    @Test
    fun `a read-only server offers no tool that changes a collection`() {
        val offered = names(allowWrites = false)

        assertTrue("search_points" in offered)
        assertTrue("list_collections" in offered)
        assertFalse("upsert_points" in offered, "a read-only server offered a write tool: $offered")
        assertFalse("delete_points" in offered, "a read-only server offered a write tool: $offered")
        assertTrue(Tools.definitions(allowWrites = false).none { it.write })
    }

    @Test
    fun `enabling writes adds them and keeps everything else`() {
        val readOnly = names(allowWrites = false)
        val withWrites = names(allowWrites = true)

        assertTrue(withWrites.containsAll(readOnly), "enabling writes dropped a read tool")
        assertEquals(setOf("upsert_points", "delete_points"), (withWrites - readOnly.toSet()).toSet())
    }

    /**
     * The distinction matters because the two are different mistakes. A model that calls `upsert_points`
     * on a read-only server should learn that the operator disabled it, not that it invented the name.
     */
    @Test
    fun `a write tool on a read-only server is refused differently from a tool that does not exist`() {
        val disabled = callFailure("upsert_points")
        val unknown = callFailure("sing_a_song")

        assertTrue("--allow-writes" in disabled, disabled)
        assertTrue("read-only" in disabled, disabled)
        assertTrue("no tool named" in unknown, unknown)
        assertFalse("--allow-writes" in unknown, unknown)
    }

    @Test
    fun `a missing required argument names the argument`() {
        val failure = callFailure("describe_collection", allowWrites = false)

        assertTrue("collection" in failure, failure)
    }

    @Test
    fun `every tool declares an object schema whose required fields are among its properties`() {
        Tools.definitions(allowWrites = true).forEach { tool ->
            assertEquals("object", tool.schema.type, "${tool.name} does not declare an object schema")
            val properties = tool.schema.properties?.keys ?: emptySet()
            tool.schema.required.orEmpty().forEach { field ->
                assertTrue(
                    field in properties,
                    "${tool.name} requires '$field' and does not describe it: $properties",
                )
            }
            assertTrue(tool.description.isNotBlank(), "${tool.name} has no description for a model to read")
        }
    }

    @Test
    fun `every tool name is unique because a client keys on it`() {
        val all = names(allowWrites = true)

        assertEquals(all.size, all.distinct().size, "two tools share a name: $all")
    }

    /**
     * Runs a tool against a client that is never reached, so the failure is the argument check rather than
     * a connection. Any tool that got as far as the transport would hang instead, which is its own signal.
     */
    private fun unreachableClient(): QdrantClient = Kdrant(host = "127.0.0.1", port = 1)

    private fun callFailure(name: String, allowWrites: Boolean = false): String {
        var message = ""
        kotlinx.coroutines.test.runTest {
            val result = Tools.call(unreachableClient(), allowWrites, name, JsonObject(emptyMap()))
            assertEquals(true, result.isError, "$name was expected to fail and did not")
            message = result.content.first().let { block ->
                (block as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
            }
        }
        return message
    }
}
