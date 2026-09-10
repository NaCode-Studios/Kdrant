@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.Distance
import dev.kdrant.model.Expression
import dev.kdrant.model.Filter
import dev.kdrant.model.Memory
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.VectorDatatype
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The wire shapes Qdrant 1.19 added. Each is asserted against the spelling in that release's own
 * OpenAPI document, because three of them are close enough to an existing shape to be got wrong
 * silently: a prefix option serialized as an object is accepted and enables nothing, a slice with
 * index and total transposed reads a different part of the collection, and a memory tier written
 * beside an `on_disk` flag only means something if the caller knows which of the two wins.
 */
class Qdrant119SurfaceTest {

    private fun filterJson(configure: FilterBuilder.() -> Unit): String =
        KdrantJson.encodeToString(Filter.serializer(), filter(configure))

    private fun searchJson(configure: SearchBuilder.() -> Unit): String =
        KdrantJson.encodeToString(SearchRequest.serializer(), SearchBuilder().apply(configure).build())

    private fun indexJson(configure: PayloadIndexBuilder.() -> Unit): String =
        KdrantJson.encodeToString(
            PayloadIndexParams.serializer(),
            PayloadIndexBuilder().apply(configure).build(),
        )

    private fun collectionJson(configure: CreateCollectionBuilder.() -> Unit): String =
        KdrantJson.encodeToString(
            CreateCollectionRequest.serializer(),
            CreateCollectionBuilder().apply(configure).build(),
        )

    // --- Prefix matching (M56) -------------------------------------------------------------------

    @Test
    fun `matchPrefix serializes as Qdrant's MatchPrefix`() {
        assertJsonEquals(
            """{"must":[{"key":"sku","match":{"prefix":"AB-"}}]}""",
            filterJson { must { matchPrefix("sku", "AB-") } },
        )
    }

    @Test
    fun `an empty prefix is rejected rather than sent`() {
        assertThrows(IllegalArgumentException::class.java) {
            filterJson { must { matchPrefix("sku", "") } }
        }
    }

    /**
     * REST spells the option as a boolean and gRPC as an empty message whose presence enables it, so
     * the core model carries the boolean and each engine renders it. This is the half of the feature a
     * caller can get wrong: an index created without it accepts a `matchPrefix` filter and matches
     * nothing.
     */
    @Test
    fun `a keyword index asks for prefix matching with a boolean`() {
        assertJsonEquals(
            """{"type":"keyword","prefix":true}""",
            indexJson { keyword { prefixMatching = true } },
        )
    }

    @Test
    fun `a keyword index that never mentions prefix matching does not send the field`() {
        assertJsonEquals(
            """{"type":"keyword","is_tenant":true}""",
            indexJson { keyword { isTenant = true } },
        )
    }

    // --- Relevance feedback (M57) ----------------------------------------------------------------

    @Test
    fun `relevance feedback carries the original query, the graded results and the strategy`() {
        assertJsonEquals(
            """
            {"query":{"relevance_feedback":{
              "target":[0.1,0.2],
              "feedback":[
                {"example":[0.3,0.4],"score":1.0},
                {"example":[0.5,0.6],"score":-0.5}
              ],
              "strategy":{"naive":{"a":1.0,"b":0.5,"c":0.25}}
            }},"limit":5}
            """.trimIndent(),
            searchJson {
                relevanceFeedback {
                    target(listOf(0.1f, 0.2f))
                    feedback(QueryInterface.Vector(listOf(0.3f, 0.4f)), 1.0f)
                    feedback(QueryInterface.Vector(listOf(0.5f, 0.6f)), -0.5f)
                    naive(a = 1.0f, b = 0.5f, c = 0.25f)
                }
                limit = 5
            },
        )
    }

    @Test
    fun `relevance feedback without any graded result is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            searchJson {
                relevanceFeedback {
                    target(listOf(0.1f))
                    naive(a = 1.0f, b = 0.5f, c = 0.25f)
                }
            }
        }
    }

    @Test
    fun `relevance feedback without a strategy is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            searchJson {
                relevanceFeedback {
                    target(listOf(0.1f))
                    feedback(QueryInterface.Vector(listOf(0.3f)), 1.0f)
                }
            }
        }
    }

    // --- Slice filtering (M58) -------------------------------------------------------------------

    @Test
    fun `slice serializes as Qdrant's SliceCondition`() {
        assertJsonEquals(
            """{"must":[{"slice":{"index":1,"total":4}}]}""",
            filterJson { must { slice(index = 1, total = 4) } },
        )
    }

    @Test
    fun `a slice index outside the split is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { filterJson { must { slice(4, 4) } } }
        assertThrows(IllegalArgumentException::class.java) { filterJson { must { slice(-1, 4) } } }
        assertThrows(IllegalArgumentException::class.java) { filterJson { must { slice(0, 0) } } }
    }

    // --- Memory tiers and 4-bit storage (M59) ----------------------------------------------------

    @Test
    fun `a collection places its vectors and its payload in memory tiers`() {
        assertJsonEquals(
            """
            {"vectors":{"size":768,"distance":"Cosine","datatype":"turbo4","memory":"cached"},
             "payload":{"memory":"cold"}}
            """.trimIndent(),
            collectionJson {
                vector {
                    size = 768
                    distance = Distance.COSINE
                    datatype = VectorDatatype.TURBO4
                    memory = Memory.CACHED
                }
                payloadMemory = Memory.COLD
            },
        )
    }

    @Test
    fun `a payload index places itself in a memory tier`() {
        assertJsonEquals(
            """{"type":"integer","lookup":true,"memory":"pinned"}""",
            indexJson { integer { lookup = true; memory = Memory.PINNED } },
        )
    }

    // --- Per-query IDF corpus --------------------------------------------------------------------

    @Test
    fun `search params compute IDF over a corpus rather than over the whole collection`() {
        assertJsonEquals(
            """
            {"query":[0.1,0.2],"limit":10,
             "params":{"idf":{"corpus":{"must":[{"key":"tenant","match":{"value":"acme"}}]}}}}
            """.trimIndent(),
            searchJson {
                query(listOf(0.1f, 0.2f))
                params { idfCorpus { must { "tenant" eq "acme" } } }
            },
        )
    }

    // --- Formula expressions ---------------------------------------------------------------------

    @Test
    fun `max, min and acosh serialize as Qdrant's expression variants`() {
        val cases = listOf(
            """{"max":["${'$'}score",2.0]}""" to Expression.max(Expression.score, Expression.of(2)),
            """{"min":["${'$'}score",2.0]}""" to Expression.min(Expression.score, Expression.of(2)),
            """{"acosh":"${'$'}score"}""" to Expression.Acosh(Expression.score),
        )
        cases.forEach { (expected, expression) ->
            assertJsonEquals(expected, KdrantJson.encodeToString(Expression.serializer(), expression))
        }
    }
}
