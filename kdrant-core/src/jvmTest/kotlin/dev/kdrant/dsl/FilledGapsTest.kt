@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.dsl

import dev.kdrant.assertJsonEquals
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.CollectionParamsDiff
import dev.kdrant.model.CompressionRatio
import dev.kdrant.model.Memory
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.QuantizationConfig
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.SnowballLanguage
import dev.kdrant.model.StemmingAlgorithm
import dev.kdrant.model.TurboQuantBitSize
import dev.kdrant.model.UpdateCollectionRequest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The parts of Qdrant's surface this client could not reach, each filed as an issue before being fixed.
 *
 * They are together in one file because they are one kind of gap: a field Qdrant takes, that nothing here
 * could send, and whose absence was invisible because it changes how fast or how accurate an answer is
 * rather than whether one arrives.
 */
class FilledGapsTest {

    private fun indexJson(configure: PayloadIndexBuilder.() -> Unit): String =
        KdrantJson.encodeToString(
            PayloadIndexParams.serializer(),
            PayloadIndexBuilder().apply(configure).build(),
        )

    private fun searchJson(configure: SearchBuilder.() -> Unit): String =
        KdrantJson.encodeToString(SearchRequest.serializer(), SearchBuilder().apply(configure).build())

    private fun updateJson(configure: UpdateCollectionBuilder.() -> Unit): String =
        KdrantJson.encodeToString(
            UpdateCollectionRequest.serializer(),
            UpdateCollectionBuilder().apply(configure).build(),
        )

    // --- A collection's shape, changed after it exists ---------------------------------------------

    @Test
    fun `updateCollection can move a payload tier and a replication factor`() {
        assertJsonEquals(
            """{"params":{"replication_factor":2,"read_fan_out_factor":1,"payload":{"memory":"cached"}}}""",
            updateJson {
                replicationFactor = 2
                readFanOutFactor = 1
                payloadMemory = Memory.CACHED
            },
        )
    }

    /**
     * Qdrant leaves a field out of the diff unchanged, so an empty `params` object would be a request to
     * change nothing that still takes the collection's lock.
     */
    @Test
    fun `an update that changes nothing about the params omits them`() {
        assertJsonEquals("""{}""", updateJson { })
        // And one that changes something else still does not invent a params object.
        assertJsonEquals(
            """{"strict_mode_config":{"enabled":true}}""",
            updateJson { strictMode = dev.kdrant.model.StrictModeConfig(enabled = true) },
        )
    }

    @Test
    fun `a replication factor below one is refused before it is sent`() {
        assertThrows(IllegalArgumentException::class.java) { CollectionParamsDiff(replicationFactor = 0) }
        assertThrows(IllegalArgumentException::class.java) { CollectionParamsDiff(readFanOutDelayMs = -1) }
    }

    // --- Payload index options ---------------------------------------------------------------------

    @Test
    fun `an index can decline the HNSW links it would otherwise build`() {
        assertJsonEquals(
            """{"type":"keyword","is_tenant":true,"enable_hnsw":false}""",
            indexJson { keyword { isTenant = true; enableHnsw = false } },
        )
    }

    @Test
    fun `a text index carries a stemmer, and disabling it is a shape of its own`() {
        assertJsonEquals(
            """{"type":"text","lowercase":true,"ascii_folding":true,"stemmer":{"type":"snowball","language":"italian"}}""",
            indexJson {
                text {
                    lowercase = true
                    asciiFolding = true
                    stemmer = StemmingAlgorithm.Snowball(SnowballLanguage.ITALIAN)
                }
            },
        )
        assertJsonEquals(
            """{"type":"text","stemmer":{"type":"none"}}""",
            indexJson { text { stemmer = StemmingAlgorithm.Disabled } },
        )
    }

    // --- Quantization -----------------------------------------------------------------------------

    @Test
    fun `product quantization carries its compression ratio`() {
        assertJsonEquals(
            """{"product":{"compression":"x16","memory":"pinned"}}""",
            KdrantJson.encodeToString(
                QuantizationConfig.serializer(),
                QuantizationConfig.Product(CompressionRatio.X16, memory = Memory.PINNED),
            ),
        )
    }

    @Test
    fun `turboquant carries its bit size, and omits it to take the server's default`() {
        assertJsonEquals(
            """{"turbo":{"bits":"bits1_5"}}""",
            KdrantJson.encodeToString(
                QuantizationConfig.serializer(),
                QuantizationConfig.Turbo(TurboQuantBitSize.BITS_1_5),
            ),
        )
        assertJsonEquals(
            """{"turbo":{}}""",
            KdrantJson.encodeToString(QuantizationConfig.serializer(), QuantizationConfig.Turbo()),
        )
    }

    /**
     * The read side of quantization, which is the half that decides recall: a quantized collection answers
     * from the approximation unless something asks for the originals.
     */
    @Test
    fun `a search can rescore against the original vectors`() {
        assertJsonEquals(
            """{"query":[0.1,0.2],"limit":10,"params":{"quantization":{"rescore":true,"oversampling":2.0}}}""",
            searchJson {
                query(listOf(0.1f, 0.2f))
                params { rescore(oversampling = 2.0) }
            },
        )
        assertJsonEquals(
            """{"query":[0.1,0.2],"limit":10,"params":{"quantization":{"ignore":true}}}""",
            searchJson {
                query(listOf(0.1f, 0.2f))
                params { quantization = dev.kdrant.model.QuantizationSearchParams(ignore = true) }
            },
        )
    }

    @Test
    fun `oversampling below one is refused, because it cannot fill a page`() {
        assertThrows(IllegalArgumentException::class.java) {
            dev.kdrant.model.QuantizationSearchParams(oversampling = 0.5)
        }
    }
}
