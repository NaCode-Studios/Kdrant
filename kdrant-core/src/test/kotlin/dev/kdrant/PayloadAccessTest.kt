package dev.kdrant

import dev.kdrant.dsl.PayloadBuilder
import dev.kdrant.model.PointId
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PayloadAccessTest {

    @Serializable
    private data class Article(val title: String, val lang: String)

    @Test
    fun `payloadAs decodes a hit payload, ignoring unknown keys`() {
        val hit = ScoredPoint(
            id = PointId.num(1),
            score = 0.9f,
            payload = buildJsonObject {
                put("title", "Intro")
                put("lang", "en")
                put("extra", "ignored")
            },
        )
        assertEquals(Article("Intro", "en"), hit.payloadAs<Article>())
    }

    @Test
    fun `payloadAs returns null when a record has no payload`() {
        val record = Record(id = PointId.num(1), payload = null)
        assertNull(record.payloadAs<Article>())
    }

    @Test
    fun `PayloadBuilder set sugar builds heterogeneous entries including null`() {
        val payload = PayloadBuilder().apply {
            set("title", "Intro")
            set("tags", listOf("nlp", "kotlin"))
            set("note", null)
        }.build()
        assertEquals("Intro", payload["title"]!!.jsonPrimitive.content)
        assertEquals(2, payload["tags"]!!.jsonArray.size)
        assertEquals(JsonNull, payload["note"])
    }
}
