@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PointIdSerializationTest {

    @Test
    fun `numeric id serializes as an unquoted number`() {
        assertJsonEquals("1", KdrantJson.encodeToString(PointId.serializer(), PointId.num(1uL)))
    }

    @Test
    fun `uuid id serializes as a string`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertJsonEquals(
            "\"$uuid\"",
            KdrantJson.encodeToString(PointId.serializer(), PointId.uuid(uuid)),
        )
    }

    @Test
    fun `uint64 above Long MAX_VALUE is preserved losslessly`() {
        // 18446744073709551615 = ULong.MAX_VALUE, well beyond Long.MAX_VALUE.
        assertJsonEquals(
            "18446744073709551615",
            KdrantJson.encodeToString(PointId.serializer(), PointId.num(ULong.MAX_VALUE)),
        )
    }

    @Test
    fun `round-trips numeric and uuid ids`() {
        listOf(
            PointId.num(42uL),
            PointId.num(ULong.MAX_VALUE),
            PointId.uuid("550e8400-e29b-41d4-a716-446655440000"),
        ).forEach { id ->
            val encoded = KdrantJson.encodeToString(PointId.serializer(), id)
            assertEquals(id, KdrantJson.decodeFromString(PointId.serializer(), encoded))
        }
    }
}
