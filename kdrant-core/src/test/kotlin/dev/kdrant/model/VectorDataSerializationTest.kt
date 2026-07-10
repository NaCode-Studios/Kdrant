@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorDataSerializationTest {

    private fun encode(value: VectorData) =
        KdrantJson.encodeToString(VectorData.serializer(), value)

    @Test
    fun `dense vector serializes as a json array`() {
        assertJsonEquals("[0.5,0.25,0.75]", encode(VectorData.Dense(listOf(0.5f, 0.25f, 0.75f))))
    }

    @Test
    fun `named vectors serialize as a json object`() {
        assertJsonEquals(
            """{"text":[0.5],"image":[0.25,0.75]}""",
            encode(VectorData.Named(mapOf("text" to listOf(0.5f), "image" to listOf(0.25f, 0.75f)))),
        )
    }

    @Test
    fun `dense and named forms round-trip`() {
        listOf(
            VectorData.Dense(listOf(1.0f, 2.0f)),
            VectorData.Named(mapOf("a" to listOf(0.5f))),
        ).forEach { value ->
            val encoded = encode(value)
            assertEquals(value, KdrantJson.decodeFromString(VectorData.serializer(), encoded))
        }
    }
}
