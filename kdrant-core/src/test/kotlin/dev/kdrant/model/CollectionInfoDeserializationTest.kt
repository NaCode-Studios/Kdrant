@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CollectionInfoDeserializationTest {

    @Test
    fun `collection info decodes status and counts, ignoring the rest`() {
        val info = KdrantJson.decodeFromString(
            CollectionInfo.serializer(),
            """{"status":"green","points_count":42,"indexed_vectors_count":40,"segments_count":3,
               "optimizer_status":"ok","config":{"params":{}},"payload_schema":{}}""",
        )
        assertEquals(CollectionStatus.GREEN, info.status)
        assertEquals(42L, info.pointsCount)
        assertEquals(40L, info.indexedVectorsCount)
        assertEquals(3, info.segmentsCount)
    }

    @Test
    fun `null counts and other statuses are tolerated`() {
        val info = KdrantJson.decodeFromString(
            CollectionInfo.serializer(),
            """{"status":"yellow","points_count":null}""",
        )
        assertEquals(CollectionStatus.YELLOW, info.status)
        assertNull(info.pointsCount)
    }

    @Test
    fun `an unrecognized status from a newer server degrades to UNKNOWN`() {
        val info = KdrantJson.decodeFromString(
            CollectionInfo.serializer(),
            """{"status":"purple","points_count":1}""",
        )
        assertEquals(CollectionStatus.UNKNOWN, info.status)
        assertEquals(1L, info.pointsCount)
    }
}
