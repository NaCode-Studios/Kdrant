package dev.kdrant

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Asserts two JSON documents are structurally equal (key order and whitespace are ignored),
 * by parsing both into a [kotlinx.serialization.json.JsonElement] tree and comparing.
 */
internal fun assertJsonEquals(expected: String, actual: String) {
    assertEquals(
        Json.parseToJsonElement(expected),
        Json.parseToJsonElement(actual),
        "JSON mismatch.\n  expected: $expected\n  actual:   $actual",
    )
}
