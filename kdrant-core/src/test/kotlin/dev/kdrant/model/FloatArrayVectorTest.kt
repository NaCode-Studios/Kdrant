@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** M21 no-boxing hot path: the FloatArray-backed dense vector (upsert) and query serialize like their List forms. */
class FloatArrayVectorTest {

    @Test
    fun `DenseArray serializes as a bare json array, like Dense`() {
        assertJsonEquals(
            "[0.5,0.25,0.75]",
            KdrantJson.encodeToString(VectorData.serializer(), VectorData.DenseArray(floatArrayOf(0.5f, 0.25f, 0.75f))),
        )
    }

    @Test
    fun `DenseArray nested under a named vector serializes correctly`() {
        assertJsonEquals(
            """{"text":[0.5,0.25]}""",
            KdrantJson.encodeToString(
                VectorData.serializer(),
                VectorData.Named(mapOf("text" to VectorData.DenseArray(floatArrayOf(0.5f, 0.25f)))),
            ),
        )
    }

    @Test
    fun `DenseArray has content-based equality`() {
        assertEquals(VectorData.DenseArray(floatArrayOf(1f, 2f)), VectorData.DenseArray(floatArrayOf(1f, 2f)))
        assertNotEquals(VectorData.DenseArray(floatArrayOf(1f, 2f)), VectorData.DenseArray(floatArrayOf(1f, 3f)))
    }

    @Test
    fun `VectorArray query serializes as a bare json array`() {
        assertJsonEquals(
            "[1.0,2.0,3.0]",
            KdrantJson.encodeToString(QueryInterface.serializer(), QueryInterface.VectorArray(floatArrayOf(1f, 2f, 3f))),
        )
    }

    @Test
    fun `VectorArray has content-based equality`() {
        assertEquals(QueryInterface.VectorArray(floatArrayOf(1f, 2f)), QueryInterface.VectorArray(floatArrayOf(1f, 2f)))
    }
}
