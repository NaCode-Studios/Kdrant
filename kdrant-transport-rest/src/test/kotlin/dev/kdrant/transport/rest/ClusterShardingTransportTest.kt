package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.kdrantConfig
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.ReplicaState
import dev.kdrant.model.ShardKey
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** M32: cluster info, shard keys, and shard-scoped reads. */
class ClusterShardingTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val okBody = """{"result":true,"status":"ok"}"""

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): QdrantClient =
        QdrantClient(RestQdrantTransport(kdrantConfig("h", 6333) {}, MockEngine { request -> handler(request) }))

    private fun HttpRequestData.bodyJson() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun `cluster info decodes local, remote and transferring shards`() {
        val qdrant = client {
            respond(
                """{"result":{"peer_id":42,"shard_count":3,
                   "local_shards":[{"shard_id":0,"shard_key":"eu-west","points_count":120,"state":"Active"}],
                   "remote_shards":[{"shard_id":1,"shard_key":7,"peer_id":99,"state":"Dead"}],
                   "shard_transfers":[{"shard_id":2,"from":42,"to":99,"sync":true}]},"status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val info = qdrant.use { runBlocking { it.collectionClusterInfo("docs") } }

        assertEquals(42L, info.peerId)
        assertEquals(3, info.shardCount)
        assertEquals(ShardKey.Name("eu-west"), info.localShards.single().shardKey)
        assertEquals(ReplicaState.ACTIVE, info.localShards.single().state)
        assertEquals(ShardKey.Num(7u), info.remoteShards.single().shardKey)
        assertEquals(ReplicaState.DEAD, info.remoteShards.single().state)
        assertEquals(true, info.shardTransfers.single().sync)
    }

    @Test
    fun `a replica state from a newer server does not fail the whole response`() {
        val qdrant = client {
            respond(
                """{"result":{"peer_id":1,"local_shards":[{"shard_id":0,"points_count":0,"state":"Teleporting"}]},
                   "status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val info = qdrant.use { runBlocking { it.collectionClusterInfo("docs") } }

        assertEquals(ReplicaState.UNKNOWN, info.localShards.single().state)
    }

    @Test
    fun `each cluster operation posts its own single-key body`() {
        val bodies = mutableListOf<Pair<String, Map<String, Long>>>()
        val qdrant = client { request ->
            val op = request.bodyJson().entries.single()
            bodies += op.key to op.value.jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use {
            runBlocking {
                it.updateCollectionCluster("docs", ClusterOperation.MoveShard(0, fromPeerId = 1, toPeerId = 2))
                it.updateCollectionCluster("docs", ClusterOperation.ReplicateShard(1, fromPeerId = 1, toPeerId = 2))
                it.updateCollectionCluster("docs", ClusterOperation.AbortTransfer(2, fromPeerId = 1, toPeerId = 2))
                it.updateCollectionCluster("docs", ClusterOperation.DropReplica(3, peerId = 9))
            }
        }

        assertEquals(
            listOf("move_shard", "replicate_shard", "abort_transfer", "drop_replica"),
            bodies.map { it.first },
        )
        assertEquals(mapOf("shard_id" to 0L, "from_peer_id" to 1L, "to_peer_id" to 2L), bodies[0].second)
        assertEquals(mapOf("shard_id" to 3L, "peer_id" to 9L), bodies[3].second)
    }

    @Test
    fun `creating a shard key sends only what was asked for`() {
        lateinit var captured: HttpRequestData
        val qdrant = client { request ->
            captured = request
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use {
            runBlocking { it.createShardKey("docs", ShardKey.of("eu-west"), shardsNumber = 2, timeout = 30) }
        }

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/collections/docs/shards", captured.url.encodedPath)
        assertEquals("30", captured.url.parameters["timeout"])
        val body = captured.bodyJson()
        assertEquals("eu-west", body["shard_key"]!!.jsonPrimitive.content)
        assertEquals(2, body["shards_number"]!!.jsonPrimitive.content.toInt())
        assertNull(body["replication_factor"])
        assertNull(body["placement"])
    }

    @Test
    fun `a numeric shard key stays a number on the wire`() {
        lateinit var captured: HttpRequestData
        val qdrant = client { request ->
            captured = request
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }

        qdrant.use { runBlocking { it.deleteShardKey("docs", ShardKey.of(7L)) } }

        assertEquals("/collections/docs/shards/delete", captured.url.encodedPath)
        val key = captured.bodyJson()["shard_key"]!!.jsonPrimitive
        assertEquals(false, key.isString, "a numeric shard key must not be quoted")
        assertEquals(7, key.content.toInt())
    }

    @Test
    fun `a non-positive shard count is rejected before any request goes out`() {
        var calls = 0
        val qdrant = client {
            calls++
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }

        assertThrows(IllegalArgumentException::class.java) {
            qdrant.use { runBlocking { it.createShardKey("docs", ShardKey.of("k"), shardsNumber = 0) } }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `search and scroll carry the shard key into the request body`() {
        val bodies = mutableListOf<String?>()
        val qdrant = client { request ->
            bodies += request.bodyJson()["shard_key"]?.jsonPrimitive?.content
            if (request.url.encodedPath.endsWith("scroll")) {
                respond("""{"result":{"points":[],"next_page_offset":null}}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"result":{"points":[]}}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        qdrant.use {
            runBlocking {
                it.search("docs") { query(0.1f); shardKey = ShardKey.of("eu-west") }
                it.scroll("docs") { shardKey = ShardKey.of("eu-west") }.toList()
                it.search("docs") { query(0.1f) }
            }
        }

        assertEquals(listOf("eu-west", "eu-west", null), bodies)
    }
}
