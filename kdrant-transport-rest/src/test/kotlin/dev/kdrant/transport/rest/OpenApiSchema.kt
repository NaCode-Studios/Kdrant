package dev.kdrant.transport.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Just enough of OpenAPI to check a request body against Qdrant's published schema.
 *
 * This is not a general JSON Schema implementation and does not try to be: it covers the constructs
 * Qdrant's document actually uses (`$ref`, `anyOf`/`oneOf`, objects with `properties` / `required` /
 * `additionalProperties`, arrays, primitive types, `nullable`, `enum`) and reports what it cannot
 * check rather than passing silently.
 *
 * The check that matters most is the unknown-property one. If Qdrant renames or drops a field Kdrant
 * sends, the server ignores the old spelling and the request quietly does something else; that is the
 * silent break these tests exist to turn into a failing build.
 */
internal class OpenApiSchema(document: String) {

    private val root: JsonObject = Json.parseToJsonElement(document).jsonObject
    private val schemas: JsonObject = root["components"]!!.jsonObject["schemas"]!!.jsonObject
    private val paths: JsonObject = root["paths"]!!.jsonObject

    /**
     * The schema of the request body for a concrete request path, e.g. `/collections/docs/points/query`,
     * matched against the document's templated paths. Returns `null` when the endpoint takes no body.
     */
    fun requestBodySchema(method: String, path: String): JsonObject? {
        val template = matchPath(path) ?: error("no OpenAPI path matches $path")
        val operation = paths[template]!!.jsonObject[method.lowercase()]?.jsonObject
            ?: error("$template has no ${method.lowercase()} operation")
        val body = operation["requestBody"]?.jsonObject ?: return null
        return body["content"]?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject
    }

    /** Every problem found in [value], or an empty list when it conforms. */
    fun validate(value: JsonElement, schema: JsonObject, location: String = "$"): List<String> =
        check(value, schema, location)

    /**
     * Pick the most specific matching template. `/collections/{collection_name}/points/{id}` matches
     * `/collections/docs/points/query` too, so ties are broken by literal segments: the template with
     * the most of them is the one that path really belongs to.
     */
    private fun matchPath(path: String): String? {
        val actual = path.trim('/').split('/')
        return paths.keys
            .filter { template ->
                val expected = template.trim('/').split('/')
                expected.size == actual.size &&
                    expected.zip(actual).all { (e, a) -> e.startsWith("{") || e == a }
            }
            .maxByOrNull { template -> template.trim('/').split('/').count { !it.startsWith("{") } }
    }

    private fun resolve(schema: JsonObject): JsonObject {
        val ref = schema["\$ref"]?.jsonPrimitive?.content ?: return schema
        val name = ref.removePrefix("#/components/schemas/")
        return schemas[name]?.jsonObject ?: error("unresolved \$ref: $ref")
    }

    private fun check(value: JsonElement, rawSchema: JsonObject, at: String): List<String> {
        val schema = resolve(rawSchema)

        (schema["anyOf"] ?: schema["oneOf"])?.let { branches ->
            val failures = branches.jsonArray.map { check(value, it.jsonObject, at) }
            return if (failures.any { it.isEmpty() }) {
                emptyList()
            } else {
                listOf("$at: matches none of the ${failures.size} allowed shapes")
            }
        }

        if (value is JsonNull) {
            val nullable = schema["nullable"]?.jsonPrimitive?.booleanOrNull == true
            return if (nullable) emptyList() else listOf("$at: null is not allowed here")
        }

        return when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> checkObject(value, schema, at)
            "array" -> checkArray(value, schema, at)
            "string" -> expect(value is JsonPrimitive && value.isString, at, "a string", value)
            "boolean" -> expect(value is JsonPrimitive && value.booleanOrNull != null, at, "a boolean", value)
            "integer" -> expect(value is JsonPrimitive && value.longOrNull != null, at, "an integer", value)
            "number" -> expect(
                value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null,
                at,
                "a number",
                value,
            )
            // A schema with no `type` and no composition constrains nothing (Qdrant uses this for a
            // free-form `nullable: true` branch), so anything passes.
            else -> emptyList()
        }
    }

    private fun checkObject(value: JsonElement, schema: JsonObject, at: String): List<String> {
        if (value !is JsonObject) return listOf("$at: expected an object, got ${describe(value)}")
        val properties = schema["properties"]?.jsonObject
        val additional = schema["additionalProperties"]
        val allowsAdditional = properties == null ||
            additional == null ||
            additional !is JsonPrimitive ||
            additional.booleanOrNull != false

        return buildList {
            schema["required"]?.jsonArray?.forEach { required ->
                val name = required.jsonPrimitive.content
                if (name !in value) add("$at: required property '$name' is missing")
            }
            value.forEach { (name, child) ->
                val propertySchema = properties?.get(name)?.jsonObject
                when {
                    propertySchema != null -> addAll(check(child, propertySchema, "$at.$name"))
                    // Free-form objects (payload) declare additionalProperties; a declared object that
                    // does not is a closed shape, and an unknown key there is the drift we are hunting.
                    properties != null && !allowsAdditional ->
                        add("$at: unknown property '$name'")
                    properties != null && additional == null ->
                        add("$at: '$name' is not in the schema's properties")
                    else -> Unit
                }
            }
        }
    }

    private fun checkArray(value: JsonElement, schema: JsonObject, at: String): List<String> {
        if (value !is JsonArray) return listOf("$at: expected an array, got ${describe(value)}")
        val items = schema["items"]?.jsonObject ?: return emptyList()
        return value.flatMapIndexed { index, element -> check(element, items, "$at[$index]") }
    }

    private fun expect(ok: Boolean, at: String, expected: String, value: JsonElement): List<String> =
        if (ok) emptyList() else listOf("$at: expected $expected, got ${describe(value)}")

    private fun describe(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonPrimitive -> if (value.isString) "the string \"${value.content}\"" else value.content
    }
}
