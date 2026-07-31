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

    @Test
    fun `the config read-back carries the vectors a correctness check needs`() {
        val info = KdrantJson.decodeFromString(
            CollectionInfo.serializer(),
            """{"status":"green","config":{"params":{
                 "vectors":{"text":{"size":768,"distance":"Cosine"}},
                 "sparse_vectors":{"bm25":{"modifier":"idf"}},
                 "shard_number":2,"replication_factor":1,"on_disk_payload":true}},
               "payload_schema":{"lang":{"data_type":"keyword","points":42}}}""",
        )

        val params = info.config!!.params!!
        assertEquals(VectorsConfig.Named(mapOf("text" to VectorParams(768, Distance.COSINE))), params.vectors)
        assertEquals(setOf("bm25"), params.sparseVectors!!.keys)
        assertEquals(2, params.shardNumber)
        assertEquals(true, params.onDiskPayload)
        assertEquals(PayloadSchemaType.KEYWORD, info.payloadSchema["lang"]!!.schemaType)
        assertEquals(42L, info.payloadSchema["lang"]!!.points)
    }

    @Test
    fun `an index type this client does not know does not fail the whole response`() {
        val info = KdrantJson.decodeFromString(
            CollectionInfo.serializer(),
            """{"status":"green","payload_schema":{"embedding":{"data_type":"tensor","points":7}}}""",
        )

        assertEquals("tensor", info.payloadSchema["embedding"]!!.dataType)
        assertNull(info.payloadSchema["embedding"]!!.schemaType)
    }
}
