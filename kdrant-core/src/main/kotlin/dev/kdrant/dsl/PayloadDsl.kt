package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Payload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a point [Payload] (a JSON object) with heterogeneous values.
 *
 * Note: for a bare `null` value the typed [put] overloads are ambiguous — use [putAny] instead.
 */
@KdrantDsl
public class PayloadBuilder {
    private val entries: MutableMap<String, JsonElement> = linkedMapOf()

    public fun put(key: String, value: String) { entries[key] = JsonPrimitive(value) }
    public fun put(key: String, value: Number) { entries[key] = JsonPrimitive(value) }
    public fun put(key: String, value: Boolean) { entries[key] = JsonPrimitive(value) }
    public fun put(key: String, value: JsonElement) { entries[key] = value }

    /**
     * Put an arbitrary value, converted with [anyToJsonElement]
     * (String/Number/Boolean/Enum/List/Map, or `null`). Use this for `null` values.
     */
    public fun putAny(key: String, value: Any?) { entries[key] = anyToJsonElement(value) }

    /**
     * Index-assignment sugar for [putAny], accepting any supported value (including `null`):
     * `payload["title"] = "Intro"`, `payload["tags"] = listOf("nlp", "kotlin")`, `payload["note"] = null`.
     */
    public operator fun set(key: String, value: Any?) { putAny(key, value) }

    internal fun build(): Payload = JsonObject(entries)
}

/** Convenience: build a [Payload] from key/value pairs, e.g. `payloadOf("lang" to "it", "year" to 2024)`. */
public fun payloadOf(vararg pairs: Pair<String, Any?>): Payload =
    JsonObject(pairs.associate { (key, value) -> key to anyToJsonElement(value) })

/**
 * Best-effort conversion of a Kotlin value to a [JsonElement] for use in a payload.
 * Supports null, existing [JsonElement]s, String, Number, Boolean, Enum, Iterable and Map;
 * throws for anything else so mistakes surface early instead of serializing silently wrong.
 *
 * Caveats: map keys are converted with `toString()` (last value wins on collision), and only
 * acyclic structures are supported (a self-referential collection would overflow the stack).
 */
internal fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is Iterable<*> -> JsonArray(value.map { anyToJsonElement(it) })
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) })
    else -> throw IllegalArgumentException(
        "Unsupported payload value type ${value::class.simpleName} for value: $value",
    )
}
