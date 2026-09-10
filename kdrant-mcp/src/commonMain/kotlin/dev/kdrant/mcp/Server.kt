package dev.kdrant.mcp

import dev.kdrant.QdrantClient
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject

/**
 * An MCP server over one Qdrant.
 *
 * The tools are in [Tools], which knows nothing about a transport, so the surface a model meets can be
 * asserted without a process or a handshake. This is the wiring: the capabilities, the instructions the
 * client shows a model, and the registration.
 */
internal fun mcpServer(
    client: QdrantClient,
    allowWrites: Boolean,
    version: String = VERSION,
): Server {
    val server = Server(
        serverInfo = Implementation(name = "kdrant", version = version, title = "Kdrant for Qdrant"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = instructions(allowWrites),
    )
    Tools.definitions(allowWrites).forEach { tool ->
        server.addTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.schema,
        ) { request ->
            Tools.call(client, allowWrites, tool.name, request.arguments ?: JsonObject(emptyMap()))
        }
    }
    return server
}

/**
 * What the client tells the model before it calls anything.
 *
 * The one thing worth spending words on is the order: a model that searches before it describes sends a
 * vector of the wrong dimension and gets an error it cannot fix, because nothing it has seen says how
 * wide the collection is.
 */
private fun instructions(allowWrites: Boolean): String = buildString {
    append(
        "Search and read a Qdrant vector database. Call describe_collection before search_points: it " +
            "reports the vector dimension the collection expects and which payload fields are indexed, " +
            "and a query vector of the wrong width is refused. ",
    )
    append(
        if (allowWrites) {
            "This server was started with writes enabled, so upsert_points and delete_points change " +
                "somebody's index. Say what you are about to write before writing it."
        } else {
            "This server is read-only. There is no tool here that changes a collection."
        },
    )
}

internal const val VERSION: String = "2.3.0"
