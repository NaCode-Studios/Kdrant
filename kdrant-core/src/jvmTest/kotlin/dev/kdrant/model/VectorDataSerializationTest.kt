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
            encode(
                VectorData.Named(
                    mapOf(
                        "text" to VectorData.Dense(listOf(0.5f)),
                        "image" to VectorData.Dense(listOf(0.25f, 0.75f)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `dense and named forms round-trip`() {
        listOf(
            VectorData.Dense(listOf(1.0f, 2.0f)),
            VectorData.Named(mapOf("a" to VectorData.Dense(listOf(0.5f)))),
        ).forEach { value ->
            val encoded = encode(value)
            assertEquals(value, KdrantJson.decodeFromString(VectorData.serializer(), encoded))
        }
    }

    @Test
    fun `sparse vector serializes and round-trips`() {
        val sparse = VectorData.Sparse(indices = listOf(1, 5, 9), values = listOf(0.2f, 0.8f, 0.1f))
        assertJsonEquals("""{"indices":[1,5,9],"values":[0.2,0.8,0.1]}""", encode(sparse))
        assertEquals(sparse, KdrantJson.decodeFromString(VectorData.serializer(), encode(sparse)))
    }

    @Test
    fun `multi-vector serializes and round-trips`() {
        val multi = VectorData.MultiDense(listOf(listOf(0.1f, 0.2f), listOf(0.3f, 0.4f)))
        assertJsonEquals("[[0.1,0.2],[0.3,0.4]]", encode(multi))
        assertEquals(multi, KdrantJson.decodeFromString(VectorData.serializer(), encode(multi)))
    }

    @Test
    fun `mixed named dense and sparse vectors round-trip`() {
        val named = VectorData.Named(
            mapOf(
                "text" to VectorData.Dense(listOf(0.5f)),
                "keywords" to VectorData.Sparse(listOf(2, 7), listOf(0.9f, 0.4f)),
            ),
        )
        assertJsonEquals("""{"text":[0.5],"keywords":{"indices":[2,7],"values":[0.9,0.4]}}""", encode(named))
        assertEquals(named, KdrantJson.decodeFromString(VectorData.serializer(), encode(named)))
    }

    @Test
    fun `an unrecognized vector shape degrades to Raw instead of throwing`() {
        val decoded = KdrantJson.decodeFromString(VectorData.serializer(), "\"weird\"")
        org.junit.jupiter.api.Assertions.assertTrue(decoded is VectorData.Raw)
    }
}
