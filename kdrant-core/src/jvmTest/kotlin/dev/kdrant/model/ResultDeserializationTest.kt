@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultDeserializationTest {

    @Test
    fun `scored point decodes id, score and payload`() {
        val point = KdrantJson.decodeFromString(
            ScoredPoint.serializer(),
            """{"id":7,"score":0.87,"payload":{"title":"x"}}""",
        )
        assertEquals(PointId.num(7), point.id)
        assertEquals(0.87f, point.score)
        assertTrue(point.payload!!.containsKey("title"))
    }

    @Test
    fun `scroll page decodes points and the next cursor`() {
        val page = KdrantJson.decodeFromString(
            ScrollPage.serializer(),
            """{"points":[{"id":"a"},{"id":2}],"next_page_offset":2}""",
        )
        assertEquals(2, page.points.size)
        assertEquals(PointId.uuid("a"), page.points[0].id)
        assertEquals(PointId.num(2), page.nextPageOffset)
    }

    @Test
    fun `scroll page decodes a null cursor as the end of the stream`() {
        val page = KdrantJson.decodeFromString(
            ScrollPage.serializer(),
            """{"points":[],"next_page_offset":null}""",
        )
        assertNull(page.nextPageOffset)
        assertTrue(page.points.isEmpty())
    }
}
