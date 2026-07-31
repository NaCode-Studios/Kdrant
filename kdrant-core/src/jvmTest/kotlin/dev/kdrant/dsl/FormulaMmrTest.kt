@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.Expression
import dev.kdrant.model.GeoPoint
import dev.kdrant.model.SearchRequest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Formula reranking and MMR: the two query shapes deferred out of M16. */
class FormulaMmrTest {

    private fun json(configure: SearchBuilder.() -> Unit): String =
        KdrantJson.encodeToString(SearchRequest.serializer(), SearchBuilder().apply(configure).build())

    /** Built outside the builder block: inside one, `filter { }` is the builder's own member. */
    private val inStock = filter { must { "in_stock" eq true } }

    @Test
    fun `mmr turns a bare vector query into its long form and carries the parameters`() {
        assertJsonEquals(
            """{"query":{"nearest":[0.1,0.2],"mmr":{"diversity":0.7,"candidates_limit":50}},"limit":10}""",
            json {
                query(listOf(0.1f, 0.2f))
                mmr(diversity = 0.7f, candidatesLimit = 50)
            },
        )
    }

    @Test
    fun `without mmr a vector query stays in its short form`() {
        assertJsonEquals("""{"query":[0.1,0.2],"limit":10}""", json { query(listOf(0.1f, 0.2f)) })
    }

    @Test
    fun `mmr on a query that has no vector to compare against is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            json {
                orderBy("ts")
                mmr(diversity = 0.5f)
            }
        }
        assertTrue(error.message!!.contains("vector query"), error.message)
    }

    @Test
    fun `a diversity outside 0 to 1 is rejected where it is written, not by the server`() {
        assertThrows(IllegalArgumentException::class.java) { json { query(listOf(0.1f)); mmr(diversity = 1.5f) } }
        assertThrows(IllegalArgumentException::class.java) { json { query(listOf(0.1f)); mmr(candidatesLimit = 0) } }
    }

    @Test
    fun `a formula composes score, payload keys, conditions and constants`() {
        assertJsonEquals(
            """
            {"prefetch":[{"query":[0.1,0.2],"limit":100}],
             "query":{"formula":{"sum":[
                "${'$'}score",
                {"mult":["popularity",0.5]},
                {"must":[{"key":"in_stock","match":{"value":true}}]}
             ]}},
             "limit":10}
            """.trimIndent(),
            json {
                prefetch { query(listOf(0.1f, 0.2f)); limit = 100 }
                formula(
                    Expression.sum(
                        Expression.score,
                        Expression.mult(Expression.key("popularity"), Expression.of(0.5)),
                        Expression.condition(inStock),
                    ),
                )
            },
        )
    }

    @Test
    fun `defaults are sent only when there are any`() {
        assertJsonEquals(
            """{"query":{"formula":"popularity","defaults":{"popularity":0.0}},"limit":10}""",
            json { formula(Expression.key("popularity"), mapOf("popularity" to JsonPrimitive(0.0))) },
        )
        assertJsonEquals(
            """{"query":{"formula":"popularity"},"limit":10}""",
            json { formula(Expression.key("popularity")) },
        )
    }

    @Test
    fun `every decay wraps its own parameters, and optional ones stay out`() {
        assertJsonEquals(
            """
            {"query":{"formula":{"sum":[
              {"exp_decay":{"x":{"datetime_key":"published"},"scale":86400.0}},
              {"lin_decay":{"x":"age","target":0.0,"scale":10.0,"midpoint":0.25}},
              {"gauss_decay":{"x":{"geo_distance":{"origin":{"lon":9.0,"lat":45.0},"to":"where"}}}}
            ]}},"limit":10}
            """.trimIndent(),
            json {
                formula(
                    Expression.sum(
                        Expression.expDecay(Expression.DatetimeKey("published"), scale = 86_400.0),
                        Expression.linDecay(
                            Expression.key("age"),
                            target = Expression.of(0),
                            scale = 10.0,
                            midpoint = 0.25,
                        ),
                        Expression.gaussDecay(Expression.geoDistance(GeoPoint(lon = 9.0, lat = 45.0), to = "where")),
                    ),
                )
            },
        )
    }

    @Test
    fun `a decay scale that cannot produce a curve is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Expression.expDecay(Expression.key("x"), scale = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Expression.expDecay(Expression.key("x"), midpoint = 1.5)
        }
    }

    @Test
    fun `div carries its by-zero default, and arithmetic nests`() {
        assertJsonEquals(
            """
            {"query":{"formula":{"div":{
               "left":{"pow":{"base":"${'$'}score","exponent":2.0}},
               "right":{"sqrt":"views"},
               "by_zero_default":0.0}}},"limit":10}
            """.trimIndent(),
            json {
                formula(
                    Expression.Div(
                        left = Expression.Pow(Expression.score, Expression.of(2)),
                        right = Expression.Sqrt(Expression.key("views")),
                        byZeroDefault = 0.0,
                    ),
                )
            },
        )
    }
}
