package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.payloadOf
import dev.kdrant.kdrantConfig
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Direction
import dev.kdrant.model.Distance
import dev.kdrant.model.Expression
import dev.kdrant.model.GeoPoint
import dev.kdrant.model.PointId
import dev.kdrant.model.PointVectors
import dev.kdrant.model.ShardKey
import dev.kdrant.model.Tokenizer
import dev.kdrant.model.VectorData
import dev.kdrant.model.WithPayload
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Contract tests: every request body the REST engine builds is validated against Qdrant's own OpenAPI
 * document, vendored under `src/test/resources` and pinned to the Qdrant the CI matrix runs against.
 *
 * A Qdrant release that renames or drops a field Kdrant sends does not fail loudly at runtime — the
 * server ignores the old spelling and the request quietly means something else. Refreshing the pinned
 * schema turns that into a failing build instead.
 */
class QdrantContractTest {

    private data class Sent(val name: String, val method: String, val path: String, val body: String)

    private val schema = OpenApiSchema(
        checkNotNull(javaClass.getResourceAsStream("/qdrant-openapi.json")) {
            "the vendored Qdrant OpenAPI document is missing from src/test/resources"
        }.bufferedReader().readText(),
    )

    /**
     * Drives one client call per operation against a mock engine that records what went out. The
     * bodies validated below are therefore the bytes Kdrant really sends, not a hand-written sample.
     */
    private val sent: List<Sent> = buildList {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        var label = ""
        val engine = MockEngine { request ->
            (request.body as? TextContent)?.let {
                add(Sent(label, request.method.value, request.url.encodedPath, it.text))
            }
            respond(RESPONSES.getValue(request.url.encodedPath.substringAfterLast('/')), HttpStatusCode.OK, jsonHeaders)
        }
        val qdrant = QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, engine))

        fun call(name: String, block: suspend (QdrantClient) -> Unit) {
            label = name
            runBlocking { block(qdrant) }
        }

        qdrant.use {
            call("createCollection") { c ->
                c.createCollection("docs") {
                    vector { size = 768; distance = Distance.COSINE; onDisk = true }
                    sparseVector("bm25")
                    onDiskPayload = true
                    shardNumber = 2
                }
            }
            call("upsert") { c ->
                c.upsert("docs", wait = true) {
                    point(1) { vector(0.1f, 0.2f); payload("lang" to "en", "year" to 2026) }
                    point("550e8400-e29b-41d4-a716-446655440000") { vector(listOf(0.3f, 0.4f)) }
                }
            }
            call("query") { c ->
                c.search("docs") {
                    query(0.1f, 0.2f)
                    limit = 5
                    offset = 10
                    scoreThreshold = 0.5
                    withPayload = WithPayload.All
                    withVector = true
                    shardKey = ShardKey.of("eu-west")
                    params { hnswEf = 128; exact = false }
                    prefetch { query(listOf(0.1f, 0.2f)); limit = 50 }
                    filter {
                        must {
                            "lang" eq "en"
                            "year" gte 2020
                            matchAny("tag", "a", "b")
                            matchText("body", "vector search")
                            geoRadius("home", GeoPoint(45.0, 9.0), radius = 1000.0)
                            valuesCount("tags", gte = 1)
                            nested("authors") { must { "name" eq "ada" } }
                        }
                        should { isEmpty("draft"); hasVector("") }
                        mustNot { hasId(PointId.num(9)) }
                        minShould(1) { "a" eq 1; "b" eq 2 }
                    }
                }
            }
            call("queryBatch") { c -> c.searchBatch("docs") { search { query(0.1f); limit = 3 } } }
            call("queryGroups") { c ->
                c.searchGroups("docs", groupBy = "lang", groupSize = 2, limit = 4) { query(0.1f) }
            }
            call("scroll") { c ->
                c.scroll("docs", pageSize = 2) {
                    withPayload = WithPayload.Include(listOf("lang"))
                    orderBy("ts", Direction.DESC, startFrom = 100)
                    shardKey = ShardKey.of("eu-west")
                }.toList()
            }
            call("count") { c -> c.count("docs", exact = true) { must { "lang" eq "en" } } }
            call("retrieve") { c ->
                c.retrieve("docs", listOf(PointId.num(1)), withPayload = WithPayload.All, withVector = true)
            }
            call("delete") { c -> c.delete("docs") { must { "lang" eq "xx" } } }
            call("setPayload") { c ->
                c.setPayload("docs", payloadOf("reviewed" to true), DeleteSelector.Ids(listOf(PointId.num(1))))
            }
            call("deletePayload") { c ->
                c.deletePayload("docs", listOf("draft"), DeleteSelector.Ids(listOf(PointId.num(1))))
            }
            call("clearPayload") { c -> c.clearPayload("docs", DeleteSelector.Ids(listOf(PointId.num(1)))) }
            call("updateVectors") { c ->
                c.updateVectors("docs", listOf(PointVectors(PointId.num(1), VectorData.Dense(listOf(0.1f)))))
            }
            call("deleteVectors") { c ->
                c.deleteVectors("docs", listOf("text"), DeleteSelector.Ids(listOf(PointId.num(1))))
            }
            call("batchUpdate") { c ->
                c.batchUpdate("docs", wait = true) {
                    upsert { point(2) { vector(0.5f, 0.6f) } }
                    setPayload(payloadOf("reviewed" to true), byId(2L))
                    overwritePayload(payloadOf("lang" to "it"), byId(2L))
                    deletePayload(listOf("draft"), byId(2L))
                    clearPayload(byFilter { must { "stale" eq true } })
                    updateVectors(listOf(PointVectors(PointId.num(2), VectorData.Dense(listOf(0.7f)))))
                    deleteVectors(listOf("text"), byId(2L))
                    delete(byFilter { must { "lang" eq "xx" } })
                }
            }
            // Server-side inference. The round trip needs a Qdrant with a provider configured, which CI
            // does not have; what CI can hold is the request shape, against Qdrant's own schema.
            call("upsertDocument") { c ->
                c.upsert("docs", wait = true) {
                    point(3) { document("the text the server embeds", model = "jinaai/jina-embeddings-v2-base-en") }
                    point(4) { image("https://example.com/a.jpg", model = "Qdrant/clip-ViT-B-32-vision") }
                }
            }
            call("queryDocument") { c ->
                c.search("docs") {
                    queryDocument("what to look for", model = "jinaai/jina-embeddings-v2-base-en")
                    limit = 5
                }
            }
            call("createPayloadIndex") { c ->
                c.createPayloadIndex("docs", "body") {
                    text {
                        tokenizer = Tokenizer.MULTILINGUAL
                        minTokenLen = 2
                        maxTokenLen = 20
                        lowercase = true
                        phraseMatching = true
                        onDisk = true
                    }
                }
            }
            call("facet") { c -> c.facet("docs", key = "lang", limit = 10, exact = true) { must { "year" gte 2020 } } }
            call("updateAliases") { c ->
                c.updateAliases(timeout = 5) { deleteAlias("docs"); createAlias(collection = "docs-v2", alias = "docs") }
            }
            call("recoverSnapshot") { c -> c.recoverSnapshot("docs", location = "file:///s.snapshot") }
            call("updateCollectionCluster") { c ->
                c.updateCollectionCluster("docs", ClusterOperation.MoveShard(0, fromPeerId = 1, toPeerId = 2))
            }
            call("createShardKey") { c ->
                c.createShardKey(
                    "docs",
                    ShardKey.of("eu-west"),
                    shardsNumber = 2,
                    replicationFactor = 1,
                    placement = listOf(1L, 2L),
                )
            }
            call("deleteShardKey") { c -> c.deleteShardKey("docs", ShardKey.of(7L)) }
            call("queryWithMmr") { c -> c.search("docs") { query(0.1f, 0.2f); mmr(diversity = 0.7f, candidatesLimit = 50) } }
            call("queryWithFormula") { c ->
                c.search("docs") {
                    prefetch { query(listOf(0.1f, 0.2f)); limit = 100 }
                    formula(
                        Expression.sum(
                            Expression.score,
                            Expression.mult(Expression.key("popularity"), Expression.of(0.5)),
                            Expression.expDecay(Expression.DatetimeKey("published"), scale = 86_400.0),
                            Expression.Div(
                                left = Expression.Pow(Expression.score, Expression.of(2)),
                                right = Expression.Sqrt(Expression.key("views")),
                                byZeroDefault = 0.0,
                            ),
                            Expression.geoDistance(GeoPoint(lon = 9.0, lat = 45.0), to = "where"),
                        ),
                        defaults = mapOf("popularity" to JsonPrimitive(0.0)),
                    )
                }
            }
        }
    }

    @TestFactory
    fun `every request body conforms to Qdrant's published schema`(): List<DynamicTest> = sent.map { request ->
        DynamicTest.dynamicTest("${request.name}: ${request.method} ${request.path}") {
            val bodySchema = checkNotNull(schema.requestBodySchema(request.method, request.path)) {
                "${request.method} ${request.path} sends a body but the schema declares none"
            }
            val problems = schema.validate(Json.parseToJsonElement(request.body), bodySchema)
            assertTrue(problems.isEmpty()) {
                "${request.method} ${request.path} does not match Qdrant's schema:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n  body: ${request.body}"
            }
        }
    }

    @Test
    fun `the operations covered here are the ones the engine can send a body for`() {
        // A guard on the guard: if someone adds an operation with a request body and no case above,
        // the contract coverage silently stops growing with the engine.
        assertEquals(26, sent.size, "operations captured: ${sent.map { it.name }}")
    }

    @Test
    fun `the validator rejects a field Qdrant does not have`() {
        val bodySchema = schema.requestBodySchema("POST", "/collections/docs/points/scroll")!!

        val problems = schema.validate(Json.parseToJsonElement("""{"limit":1,"orderBy":"ts"}"""), bodySchema)

        assertTrue(problems.any { it.contains("orderBy") }, problems.toString())
    }

    private companion object {
        /** Minimal well-formed responses, keyed by the last path segment; the bodies are what is under test. */
        val RESPONSES: Map<String, String> = mapOf(
            "docs" to """{"result":true,"status":"ok"}""",
            "points" to """{"result":[],"status":"ok"}""",
            "query" to """{"result":{"points":[]},"status":"ok"}""",
            "batch" to """{"result":[],"status":"ok"}""",
            "groups" to """{"result":{"groups":[]},"status":"ok"}""",
            "scroll" to """{"result":{"points":[],"next_page_offset":null},"status":"ok"}""",
            "count" to """{"result":{"count":0},"status":"ok"}""",
            "delete" to """{"result":true,"status":"ok"}""",
            "payload" to """{"result":true,"status":"ok"}""",
            "clear" to """{"result":true,"status":"ok"}""",
            "vectors" to """{"result":true,"status":"ok"}""",
            "index" to """{"result":{"operation_id":0,"status":"completed"},"status":"ok"}""",
            "facet" to """{"result":{"hits":[]},"status":"ok"}""",
            "aliases" to """{"result":true,"status":"ok"}""",
            "recover" to """{"result":true,"status":"ok"}""",
            "cluster" to """{"result":true,"status":"ok"}""",
            "shards" to """{"result":true,"status":"ok"}""",
        )
    }
}
