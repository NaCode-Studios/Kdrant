@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.Distance
import dev.kdrant.model.Modifier
import dev.kdrant.model.MultiVectorComparator
import dev.kdrant.model.OptimizersConfig
import dev.kdrant.model.QuantizationConfig
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CreateCollectionBuilderTest {

    private fun build(configure: CreateCollectionBuilder.() -> Unit) =
        CreateCollectionBuilder().apply(configure).build()

    private fun json(request: CreateCollectionRequest) =
        KdrantJson.encodeToString(CreateCollectionRequest.serializer(), request)

    @Test
    fun `single-vector dsl builds the expected request`() {
        val request = build {
            vector { size = 768; distance = Distance.COSINE }
            onDiskPayload = true
        }
        assertJsonEquals(
            """{"vectors":{"size":768,"distance":"Cosine"},"on_disk_payload":true}""",
            json(request),
        )
    }

    @Test
    fun `named-vectors dsl builds a name to params map`() {
        val request = build {
            namedVector("image") { size = 512; distance = Distance.DOT; onDisk = true }
            namedVector("text") { size = 768; distance = Distance.COSINE }
        }
        assertJsonEquals(
            """
            {"vectors":{
              "image":{"size":512,"distance":"Dot","on_disk":true},
              "text":{"size":768,"distance":"Cosine"}
            }}
            """.trimIndent(),
            json(request),
        )
    }

    @Test
    fun `mixing single and named vectors is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build {
                vector { size = 4; distance = Distance.COSINE }
                namedVector("text") { size = 4; distance = Distance.COSINE }
            }
        }
    }

    @Test
    fun `declaring no vectors is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build { onDiskPayload = true }
        }
    }

    @Test
    fun `omitting the vector size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build { vector { distance = Distance.COSINE } }
        }
    }

    @Test
    fun `a non-positive vector size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build { vector { size = 0; distance = Distance.COSINE } }
        }
    }

    @Test
    fun `a non-positive shard or replication factor is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build { vector { size = 4; distance = Distance.COSINE }; shardNumber = 0 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            build { vector { size = 4; distance = Distance.COSINE }; replicationFactor = -1 }
        }
    }

    @Test
    fun `sparse and multivector config serialize`() {
        val request = build {
            namedVector("text") { size = 768; distance = Distance.COSINE }
            namedVector("colbert") { size = 128; distance = Distance.COSINE; multivector = MultiVectorComparator.MAX_SIM }
            sparseVector("keywords") { modifier = Modifier.IDF }
        }
        assertJsonEquals(
            """
            {"vectors":{
              "text":{"size":768,"distance":"Cosine"},
              "colbert":{"size":128,"distance":"Cosine","multivector_config":{"comparator":"max_sim"}}
            },
            "sparse_vectors":{"keywords":{"modifier":"idf"}}}
            """.trimIndent(),
            json(request),
        )
    }

    @Test
    fun `a sparse-only collection omits the vectors field`() {
        assertJsonEquals("""{"sparse_vectors":{"keywords":{}}}""", json(build { sparseVector("keywords") }))
    }

    @Test
    fun `quantization and optimizers config serialize on create`() {
        val request = build {
            vector { size = 4; distance = Distance.COSINE }
            optimizers = OptimizersConfig(indexingThreshold = 20000)
            quantization = QuantizationConfig.Scalar(quantile = 0.99f, alwaysRam = true)
        }
        assertJsonEquals(
            """
            {"vectors":{"size":4,"distance":"Cosine"},
            "optimizers_config":{"indexing_threshold":20000},
            "quantization_config":{"scalar":{"type":"int8","quantile":0.99,"always_ram":true}}}
            """.trimIndent(),
            json(request),
        )
    }
}
