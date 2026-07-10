@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.PointId
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.WithPayload
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

class ScrollBuilderTest {

    private fun json(offset: PointId?, configure: ScrollBuilder.() -> Unit): String =
        KdrantJson.encodeToString(ScrollRequest.serializer(), ScrollBuilder(100).apply(configure).build(offset))

    @Test
    fun `first page omits the offset`() {
        assertJsonEquals(
            """{"limit":100,"with_payload":true,"filter":{"must":[{"key":"lang","match":{"value":"en"}}]}}""",
            json(null) {
                filter { must { "lang" eq "en" } }
                withPayload = WithPayload.All
            },
        )
    }

    @Test
    fun `a later page carries the cursor`() {
        assertJsonEquals(
            """{"limit":100,"offset":42,"with_vector":true}""",
            json(PointId.num(42)) { withVector = true },
        )
    }
}
