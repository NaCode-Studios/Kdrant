@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

class CreateCollectionSerializationTest {

    private fun encode(value: CreateCollectionRequest) =
        KdrantJson.encodeToString(CreateCollectionRequest.serializer(), value)

    @Test
    fun `minimal single-vector collection omits all null fields`() {
        val request = CreateCollectionRequest(
            vectors = VectorsConfig.single(size = 768, distance = Distance.COSINE),
            onDiskPayload = true,
        )
        assertJsonEquals(
            """{"vectors":{"size":768,"distance":"Cosine"},"on_disk_payload":true}""",
            encode(request),
        )
    }

    @Test
    fun `named vectors with hnsw config serialize with snake_case keys`() {
        val request = CreateCollectionRequest(
            vectors = VectorsConfig.named(
                mapOf(
                    "text" to VectorParams(size = 768, distance = Distance.COSINE),
                ),
            ),
            hnswConfig = HnswConfig(m = 16, efConstruct = 100),
        )
        assertJsonEquals(
            """
            {
              "vectors": {"text":{"size":768,"distance":"Cosine"}},
              "hnsw_config": {"m":16,"ef_construct":100}
            }
            """.trimIndent(),
            encode(request),
        )
    }
}
