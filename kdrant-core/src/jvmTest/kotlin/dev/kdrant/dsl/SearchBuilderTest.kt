@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.Direction
import dev.kdrant.model.PointId
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.RecommendStrategy
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

    @Test
    fun `query by point id serializes to a bare id`() {
        assertJsonEquals(
            """{"query":5,"limit":3}""",
            json { query(PointId.num(5)); limit = 3 },
        )
    }

    @Test
    fun `hybrid search with prefetch and RRF fusion`() {
        assertJsonEquals(
            """
            {"prefetch":[
              {"query":[0.1,0.2],"using":"title","limit":50},
              {"query":[0.3,0.4],"using":"body","limit":50}
            ],
            "query":{"fusion":"rrf"},"limit":10}
            """.trimIndent(),
            json {
                prefetch { query(0.1f, 0.2f); using = "title"; limit = 50 }
                prefetch { query(0.3f, 0.4f); using = "body"; limit = 50 }
                rrf()
            },
        )
    }

    @Test
    fun `parameterized RRF and DBSF fusion`() {
        assertJsonEquals(
            """{"prefetch":[{"query":[0.1],"limit":5}],"query":{"rrf":{"k":60,"weights":[0.7,0.3]}},"limit":10}""",
            json { prefetch { query(0.1f); limit = 5 }; rrf(k = 60, weights = listOf(0.7f, 0.3f)) },
        )
        assertJsonEquals(
            """{"prefetch":[{"query":[0.1],"limit":5}],"query":{"fusion":"dbsf"},"limit":10}""",
            json { prefetch { query(0.1f); limit = 5 }; dbsf() },
        )
    }

    @Test
    fun `order by, sample and lookup_from`() {
        assertJsonEquals(
            """{"query":{"order_by":{"key":"year","direction":"desc"}},"limit":10}""",
            json { orderBy("year", Direction.DESC) },
        )
        assertJsonEquals("""{"query":{"order_by":"year"},"limit":10}""", json { orderBy("year") })
        assertJsonEquals("""{"query":{"sample":"random"},"limit":10}""", json { sample() })
        assertJsonEquals(
            """{"query":1,"limit":10,"lookup_from":{"collection":"other","vector":"text"}}""",
            json { query(PointId.num(1)); lookupFrom("other", "text") },
        )
    }

    @Test
    fun `sparse query and dense-plus-sparse hybrid`() {
        assertJsonEquals(
            """{"query":{"indices":[3,17],"values":[0.6,0.4]},"using":"keywords","limit":10}""",
            json { querySparse(listOf(3, 17), listOf(0.6f, 0.4f)); using = "keywords" },
        )
        assertJsonEquals(
            """
            {"prefetch":[
              {"query":[0.1,0.2],"using":"text","limit":50},
              {"query":{"indices":[3,17],"values":[0.6,0.4]},"using":"keywords","limit":50}
            ],
            "query":{"fusion":"rrf"},"limit":10}
            """.trimIndent(),
            json {
                prefetch { query(0.1f, 0.2f); using = "text"; limit = 50 }
                prefetch { querySparse(listOf(3, 17), listOf(0.6f, 0.4f)); using = "keywords"; limit = 50 }
                rrf()
            },
        )
    }

    @Test
    fun `recommend query with positive, negative and strategy`() {
        assertJsonEquals(
            """{"query":{"recommend":{"positive":[[0.1,0.2],5],"negative":[[0.9,0.8]],"strategy":"best_score"}},"limit":10}""",
            json {
                recommend {
                    positive(listOf(0.1f, 0.2f))
                    positive(PointId.num(5))
                    negative(listOf(0.9f, 0.8f))
                    strategy = RecommendStrategy.BEST_SCORE
                }
            },
        )
    }

    @Test
    fun `discover and context queries`() {
        assertJsonEquals(
            """{"query":{"discover":{"target":[0.1],"context":[{"positive":[0.2],"negative":3}]}},"limit":10}""",
            json {
                discover {
                    target(listOf(0.1f))
                    context(QueryInterface.Vector(listOf(0.2f)), QueryInterface.ById(PointId.num(3)))
                }
            },
        )
        assertJsonEquals(
            """{"query":{"context":[{"positive":[0.2],"negative":[0.9]}]},"limit":10}""",
            json { context { pair(QueryInterface.Vector(listOf(0.2f)), QueryInterface.Vector(listOf(0.9f))) } },
        )
    }
}
