package dev.kdrant.mcp

import dev.kdrant.QdrantClient
import dev.kdrant.model.PointId
import dev.kdrant.model.WithPayload
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One tool the server offers, and whether offering it is a decision somebody has to make.
 *
 * @property write true when the tool changes somebody's index. Those are off unless the operator turns
 *   them on, because letting a model write to a collection is a different risk class from letting it
 *   read one, and shipping writes on by default would be that choice made for the operator rather than
 *   by them.
 */
internal class ToolDefinition(
    val name: String,
    val description: String,
    val schema: ToolSchema,
    val write: Boolean = false,
    val run: suspend (QdrantClient, JsonObject) -> JsonElement,
)

/**
 * The tool surface, and the argument parsing under it.
 *
 * Separated from the server so it can be tested without a transport: the schemas, the gating of the
 * write tools and the failure messages are the parts a caller meets, and none of them need a process or
 * a handshake to assert.
 */
internal object Tools {

    /** Every tool, read-only first. [definitions] filters by what the operator allowed. */
    fun definitions(allowWrites: Boolean): List<ToolDefinition> =
        all.filter { allowWrites || !it.write }

    /**
     * Runs one tool, or reports that it is not available.
     *
     * A write tool that exists but was not enabled gets a different message from a name that does not
     * exist, because the two are different mistakes: one is a server the operator configured that way,
     * and the other is a model inventing a tool.
     *
     * Over MCP the first of those is answered earlier than here: a read-only server never registers the
     * write tools, so the SDK rejects the name before any handler runs. What tells the model why is the
     * instructions the server sends at initialize. This branch is the guard for a caller holding [Tools]
     * directly, and the reason it stays is that the gate should not depend on registration alone.
     */
    suspend fun call(
        client: QdrantClient,
        allowWrites: Boolean,
        name: String,
        arguments: JsonObject,
    ): CallToolResult {
        val tool = all.firstOrNull { it.name == name }
            ?: return failure("no tool named '$name'. Call tools/list for what this server offers.")
        if (tool.write && !allowWrites) {
            return failure(
                "'$name' writes to the collection and this server was started read-only. " +
                    "Restart it with --allow-writes if that is intended.",
            )
        }
        return runCatching { tool.run(client, arguments) }.fold(
            onSuccess = { result -> success(result) },
            // The model is the reader here, so the message has to be the whole diagnosis: it cannot look
            // at a stack trace and it will try again with whatever this says.
            onFailure = { cause -> failure(cause.message ?: cause::class.simpleName ?: "the call failed") },
        )
    }

    // --- Tools ------------------------------------------------------------------------------------

    private val all: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "list_collections",
            description = "List the collections on this Qdrant, with their point counts and status.",
            schema = ToolSchema(),
        ) { client, _ ->
            buildJsonArray {
                client.listCollections().forEach { collection ->
                    val info = runCatching { client.getCollection(collection.name) }.getOrNull()
                    add(
                        buildJsonObject {
                            put("name", collection.name)
                            put("points", info?.pointsCount)
                            put("status", info?.status?.name?.lowercase())
                        },
                    )
                }
            }
        },
        ToolDefinition(
            name = "describe_collection",
            description = "The vector configuration, shard layout and payload indexes of one collection. " +
                "Worth calling before search_points, because it names the vector sizes and the indexed " +
                "payload fields a filter can use.",
            schema = objectSchema("collection" to string("The collection name."), required = listOf("collection")),
        ) { client, arguments ->
            val info = client.getCollection(arguments.text("collection"))
            buildJsonObject {
                put("status", info.status.name.lowercase())
                put("points", info.pointsCount)
                put("shards", info.config?.params?.shardNumber)
                putJsonObject("payload_indexes") {
                    info.payloadSchema.forEach { (field, schema) -> put(field, schema.dataType) }
                }
            }
        },
        ToolDefinition(
            name = "search_points",
            description = "Nearest-neighbour search with a dense vector. The vector has to have the " +
                "dimension the collection was created with; describe_collection reports it.",
            schema = objectSchema(
                "collection" to string("The collection to search."),
                "vector" to numberArray("The query vector."),
                "limit" to integer("How many hits to return. Defaults to 10."),
                "with_payload" to boolean("Return each hit's payload. Defaults to true."),
                required = listOf("collection", "vector"),
            ),
        ) { client, arguments ->
            val hits = client.search(arguments.text("collection")) {
                query(arguments.floats("vector"))
                limit = arguments.int("limit") ?: DEFAULT_LIMIT
                withPayload = if (arguments.bool("with_payload") != false) WithPayload.All else WithPayload.None
            }
            buildJsonArray {
                hits.forEach { hit ->
                    add(
                        buildJsonObject {
                            put("id", hit.id.toJson())
                            put("score", hit.score)
                            hit.payload?.let { put("payload", it) }
                        },
                    )
                }
            }
        },
        ToolDefinition(
            name = "scroll_points",
            description = "Read points in id order without a query vector, optionally filtered by an " +
                "exact payload match. For looking at what a collection holds rather than searching it.",
            schema = objectSchema(
                "collection" to string("The collection to read."),
                "limit" to integer("How many points to return. Defaults to 10."),
                "match_key" to string("A payload key to filter on, with match_value."),
                "match_value" to string("The value match_key has to equal."),
                required = listOf("collection"),
            ),
        ) { client, arguments ->
            val limit = arguments.int("limit") ?: DEFAULT_LIMIT
            val key = arguments.textOrNull("match_key")
            val value = arguments.textOrNull("match_value")
            val records = client.scroll(arguments.text("collection"), pageSize = limit) {
                withPayload = WithPayload.All
                if (key != null && value != null) filter { must { key eq value } }
            }.take(limit).toList()
            buildJsonArray {
                records.forEach { record ->
                    add(
                        buildJsonObject {
                            put("id", record.id.toJson())
                            record.payload?.let { put("payload", it) }
                        },
                    )
                }
            }
        },
        ToolDefinition(
            name = "retrieve_points",
            description = "Fetch points by id.",
            schema = objectSchema(
                "collection" to string("The collection to read from."),
                "ids" to stringArray("The point ids, as strings. A numeric id may be sent as a number."),
                required = listOf("collection", "ids"),
            ),
        ) { client, arguments ->
            val records = client.retrieve(
                arguments.text("collection"),
                arguments.pointIds("ids"),
                withPayload = WithPayload.All,
            )
            buildJsonArray {
                records.forEach { record ->
                    add(
                        buildJsonObject {
                            put("id", record.id.toJson())
                            record.payload?.let { put("payload", it) }
                        },
                    )
                }
            }
        },
        ToolDefinition(
            name = "count_points",
            description = "Count the points in a collection, optionally filtered by an exact payload match.",
            schema = objectSchema(
                "collection" to string("The collection to count."),
                "match_key" to string("A payload key to filter on, with match_value."),
                "match_value" to string("The value match_key has to equal."),
                required = listOf("collection"),
            ),
        ) { client, arguments ->
            val key = arguments.textOrNull("match_key")
            val value = arguments.textOrNull("match_value")
            val count = if (key != null && value != null) {
                client.count(arguments.text("collection")) { must { key eq value } }
            } else {
                client.count(arguments.text("collection"))
            }
            JsonPrimitive(count)
        },
        ToolDefinition(
            name = "upsert_points",
            description = "Write points into a collection. Each point needs an id and a vector, and may " +
                "carry a payload object.",
            schema = objectSchema(
                "collection" to string("The collection to write to."),
                "points" to objectArray("Objects of id, vector and optional payload."),
                required = listOf("collection", "points"),
            ),
            write = true,
        ) { client, arguments ->
            val points = arguments.objects("points")
            client.upsert(arguments.text("collection"), wait = true) {
                points.forEach { entry ->
                    val values = entry.floats("vector")
                    point(entry.text("id")) {
                        vector(values)
                        (entry["payload"] as? JsonObject)?.let { payload(it) }
                    }
                }
            }
            buildJsonObject { put("upserted", points.size) }
        },
        ToolDefinition(
            name = "delete_points",
            description = "Delete points by id.",
            schema = objectSchema(
                "collection" to string("The collection to delete from."),
                "ids" to stringArray("The point ids to delete."),
                required = listOf("collection", "ids"),
            ),
            write = true,
        ) { client, arguments ->
            val ids = arguments.pointIds("ids")
            client.delete(arguments.text("collection"), ids, wait = true)
            buildJsonObject { put("deleted", ids.size) }
        },
    )

    // --- Results ----------------------------------------------------------------------------------

    /**
     * Both halves of the result, every time.
     *
     * `content` is what a model reads and `structuredContent` is what a program reads, and filling in
     * only the first hands the model a wall of JSON to re-parse out of a string. It costs nothing to
     * send both.
     */
    private fun success(result: JsonElement): CallToolResult = CallToolResult(
        content = listOf(TextContent(result.toString())),
        structuredContent = result as? JsonObject ?: buildJsonObject { put("result", result) },
    )

    private fun failure(message: String): CallToolResult = CallToolResult(
        content = listOf(TextContent(message)),
        isError = true,
    )

    // --- Schemas ----------------------------------------------------------------------------------

    private fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String>): ToolSchema =
        ToolSchema(
            properties = buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } },
            required = required,
        )

    private fun string(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun numberArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "number") }
    }

    private fun stringArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    private fun objectArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "object") }
    }

    // --- Argument reading -------------------------------------------------------------------------
    //
    // A model sends what it sends. Every one of these says which argument was wrong rather than throwing
    // a cast exception, because the message is the only thing the model gets to learn from.

    private fun JsonObject.text(name: String): String =
        textOrNull(name) ?: error("'$name' is required and has to be a string")

    private fun JsonObject.textOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let {
        runCatching { it.int }.getOrElse { error("'$name' has to be an integer, was ${this[name]}") }
    }

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.floats(name: String): List<Float> {
        val array = this[name] as? JsonArray ?: error("'$name' has to be an array of numbers")
        return array.map { element ->
            runCatching { element.jsonPrimitive.double.toFloat() }
                .getOrElse { error("'$name' has to contain only numbers, found $element") }
        }.also { require(it.isNotEmpty()) { "'$name' cannot be empty" } }
    }

    private fun JsonObject.objects(name: String): List<JsonObject> {
        val array = this[name] as? JsonArray ?: error("'$name' has to be an array of objects")
        return array.map { it as? JsonObject ?: error("'$name' has to contain only objects, found $it") }
    }

    /**
     * Ids arrive as whatever JSON the model produced. A numeric id is a number here and a uuid is a
     * string, and Qdrant treats 1 and "1" as different points, so guessing wrong returns the wrong
     * thing rather than an error.
     */
    private fun JsonObject.pointIds(name: String): List<PointId> {
        val array = this[name]?.let { it as? JsonArray } ?: error("'$name' has to be an array of ids")
        require(array.isNotEmpty()) { "'$name' cannot be empty" }
        return array.map { element ->
            val primitive = element as? JsonPrimitive ?: error("'$name' has to contain ids, found $element")
            primitive.content.toLongOrNull()?.let(PointId::num) ?: PointId.uuid(primitive.content)
        }
    }

    /**
     * An id as JSON a model can hand straight back to `retrieve_points`.
     *
     * `PointId.toString()` is the data class form, so a numeric id came out as `Num(value=1)`, which is
     * not an id and cannot be used for anything. A number stays a number and a uuid stays a string,
     * because Qdrant treats 1 and "1" as different points.
     */
    private fun PointId.toJson(): JsonPrimitive = when (this) {
        is PointId.Num -> JsonPrimitive(value.toLong())
        is PointId.Uuid -> JsonPrimitive(value)
    }

    private const val DEFAULT_LIMIT = 10
}
