@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.PointStruct
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UpsertBuilderTest {

    private fun json(configure: UpsertBuilder.() -> Unit): String {
        val points = UpsertBuilder().apply(configure).build()
        return KdrantJson.encodeToString(ListSerializer(PointStruct.serializer()), points)
    }

    @Test
    fun `single dense vector with pair payload`() {
        assertJsonEquals(
            """[{"id":1,"vector":[0.5,0.25],"payload":{"lang":"it","year":2024}}]""",
            json {
                point(1) {
                    vector(0.5f, 0.25f)
                    payload("lang" to "it", "year" to 2024)
                }
            },
        )
    }

    @Test
    fun `uuid id with named vectors and no payload`() {
        assertJsonEquals(
            """
            [{
              "id":"550e8400-e29b-41d4-a716-446655440000",
              "vector":{"text":[0.5],"image":[0.25,0.75]}
            }]
            """.trimIndent(),
            json {
                point("550e8400-e29b-41d4-a716-446655440000") {
                    vector("text" to listOf(0.5f), "image" to listOf(0.25f, 0.75f))
                }
            },
        )
    }

    @Test
    fun `payload DSL block builds heterogeneous values`() {
        assertJsonEquals(
            """[{"id":2,"vector":[1.0],"payload":{"price":12.5,"tags":["a","b"]}}]""",
            json {
                point(2) {
                    vector(1.0f)
                    payload {
                        put("price", 12.5)
                        putAny("tags", listOf("a", "b"))
                    }
                }
            },
        )
    }

    @Test
    fun `payloadOf converts common value types`() {
        val payload = payloadOf(
            "s" to "x",
            "n" to 3,
            "d" to 1.5,
            "b" to true,
            "list" to listOf(1, 2),
            "nested" to mapOf("k" to "v"),
            "nil" to null,
        )
        assertJsonEquals(
            """{"s":"x","n":3,"d":1.5,"b":true,"list":[1,2],"nested":{"k":"v"},"nil":null}""",
            KdrantJson.encodeToString(JsonObject.serializer(), payload),
        )
    }

    @Test
    fun `empty upsert is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpsertBuilder().build()
        }
    }

    @Test
    fun `point without a vector is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpsertBuilder().apply { point(1) { payload("a" to 1) } }.build()
        }
    }
}
