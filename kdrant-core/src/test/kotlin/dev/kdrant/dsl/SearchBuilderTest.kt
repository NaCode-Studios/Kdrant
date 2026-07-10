@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.WithPayload
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SearchBuilderTest {

    private fun json(configure: SearchBuilder.() -> Unit): String =
        KdrantJson.encodeToString(SearchRequest.serializer(), SearchBuilder().apply(configure).build())

    @Test
    fun `search with filter, payload projection and params`() {
        assertJsonEquals(
            """
            {"query":[0.2,0.1,0.9],"using":"text","limit":5,"score_threshold":0.5,
             "with_payload":{"include":["title"]},
             "filter":{"must":[{"key":"lang","match":{"value":"en"}}]},
             "params":{"hnsw_ef":128,"exact":false}}
            """.trimIndent(),
            json {
                query(0.2f, 0.1f, 0.9f)
                using = "text"
                limit = 5
                scoreThreshold = 0.5
                withPayload = WithPayload.include("title")
                filter { must { "lang" eq "en" } }
                params { hnswEf = 128; exact = false }
            },
        )
    }

    @Test
    fun `a missing query vector is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchBuilder().apply { limit = 5 }.build()
        }
    }

    @Test
    fun `a non-positive limit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchBuilder().apply { query(1f); limit = 0 }.build()
        }
    }

    @Test
    fun `an empty query vector is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchBuilder().apply { query(emptyList()) }.build()
        }
    }
}
