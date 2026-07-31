@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorsConfigSerializationTest {

    private fun encode(value: VectorsConfig) =
        KdrantJson.encodeToString(VectorsConfig.serializer(), value)

    @Test
    fun `single anonymous vector serializes as a bare params object with no null fields`() {
        val config = VectorsConfig.single(size = 768, distance = Distance.COSINE)
        assertJsonEquals("""{"size":768,"distance":"Cosine"}""", encode(config))
    }

    @Test
    fun `named vectors serialize as a name to params map`() {
        val config = VectorsConfig.named(
            mapOf(
                "image" to VectorParams(size = 512, distance = Distance.DOT, onDisk = true),
                "text" to VectorParams(size = 768, distance = Distance.COSINE),
            ),
        )
        assertJsonEquals(
            """
            {
              "image": {"size":512,"distance":"Dot","on_disk":true},
              "text":  {"size":768,"distance":"Cosine"}
            }
            """.trimIndent(),
            encode(config),
        )
    }

    @Test
    fun `single form round-trips`() {
        val config = VectorsConfig.single(size = 1536, distance = Distance.EUCLID)
        assertEquals(config, KdrantJson.decodeFromString(VectorsConfig.serializer(), encode(config)))
    }

    @Test
    fun `named form round-trips`() {
        val config = VectorsConfig.named(
            mapOf("text" to VectorParams(size = 768, distance = Distance.COSINE)),
        )
        assertEquals(config, KdrantJson.decodeFromString(VectorsConfig.serializer(), encode(config)))
    }
}
