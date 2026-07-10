@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WithPayloadSerializationTest {

    private fun encode(value: WithPayload) =
        KdrantJson.encodeToString(WithPayload.serializer(), value)

    private fun decode(json: String) =
        KdrantJson.decodeFromString(WithPayload.serializer(), json)

    @Test
    fun `All serializes as true`() {
        assertJsonEquals("true", encode(WithPayload.All))
    }

    @Test
    fun `None serializes as false`() {
        assertJsonEquals("false", encode(WithPayload.None))
    }

    @Test
    fun `Include serializes as an include object`() {
        assertJsonEquals(
            """{"include":["city","price"]}""",
            encode(WithPayload.include("city", "price")),
        )
    }

    @Test
    fun `Exclude serializes as an exclude object`() {
        assertJsonEquals(
            """{"exclude":["internal"]}""",
            encode(WithPayload.exclude("internal")),
        )
    }

    @Test
    fun `boolean and object forms decode back`() {
        assertEquals(WithPayload.All, decode("true"))
        assertEquals(WithPayload.None, decode("false"))
        assertEquals(WithPayload.Include(listOf("a", "b")), decode("""{"include":["a","b"]}"""))
        assertEquals(WithPayload.Exclude(listOf("a")), decode("""{"exclude":["a"]}"""))
    }

    @Test
    fun `bare array shorthand decodes as Include`() {
        assertEquals(WithPayload.Include(listOf("description", "price")), decode("""["description","price"]"""))
    }
}
