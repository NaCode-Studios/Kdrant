package dev.kdrant.cli

import dev.kdrant.model.CollectionParams
import dev.kdrant.model.Distance
import dev.kdrant.model.Modifier
import dev.kdrant.model.MultiVectorComparator
import dev.kdrant.model.MultiVectorConfig
import dev.kdrant.model.SparseVectorParams
import dev.kdrant.model.VectorDatatype
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `kdrant migrate a b` has to create `b`, and the first release build found that it did not: the
 * migration refused because the target did not exist, which is true of every collection that has not
 * been made yet and therefore of the whole command.
 *
 * The configuration is taken from the source rather than asked for, so what is under test is that it
 * arrives intact. A vector size carried wrong is a migration that fails on its first upsert; a
 * distance carried wrong is one that succeeds and returns the wrong neighbours.
 */
class MigrateTargetTest {

    @Test
    fun `a single anonymous vector keeps its size and distance and storage`() {
        val target = Commands.targetConfigurationOf(
            CollectionParams(
                vectors = VectorsConfig.Single(
                    VectorParams(
                        size = 1536,
                        distance = Distance.COSINE,
                        onDisk = true,
                        datatype = VectorDatatype.FLOAT16,
                    ),
                ),
                onDiskPayload = true,
            ),
            shards = null,
            replicas = null,
        )

        val vectors = target.vectors as VectorsConfig.Single
        assertEquals(1536L, vectors.params.size)
        assertEquals(Distance.COSINE, vectors.params.distance)
        assertEquals(true, vectors.params.onDisk)
        assertEquals(VectorDatatype.FLOAT16, vectors.params.datatype)
        assertEquals(true, target.onDiskPayload)
    }

    @Test
    fun `named vectors keep their names and a multi-vector keeps its comparator`() {
        val target = Commands.targetConfigurationOf(
            CollectionParams(
                vectors = VectorsConfig.Named(
                    mapOf(
                        "text" to VectorParams(size = 768, distance = Distance.COSINE),
                        "colbert" to VectorParams(
                            size = 128,
                            distance = Distance.DOT,
                            multivectorConfig = MultiVectorConfig(MultiVectorComparator.MAX_SIM),
                        ),
                    ),
                ),
            ),
            shards = null,
            replicas = null,
        )

        val vectors = target.vectors as VectorsConfig.Named
        assertEquals(setOf("text", "colbert"), vectors.vectors.keys)
        assertEquals(768L, vectors.vectors.getValue("text").size)
        assertEquals(
            MultiVectorComparator.MAX_SIM,
            vectors.vectors.getValue("colbert").multivectorConfig?.comparator,
        )
    }

    @Test
    fun `a sparse vector keeps its modifier because IDF changes what the scores mean`() {
        val target = Commands.targetConfigurationOf(
            CollectionParams(
                vectors = VectorsConfig.Single(VectorParams(size = 4, distance = Distance.DOT)),
                sparseVectors = mapOf("keywords" to SparseVectorParams(modifier = Modifier.IDF)),
            ),
            shards = null,
            replicas = null,
        )

        assertEquals(Modifier.IDF, target.sparseVectors?.getValue("keywords")?.modifier)
    }

    @Test
    fun `the sharding is the source's unless the caller overrides it`() {
        val source = CollectionParams(
            vectors = VectorsConfig.Single(VectorParams(size = 4, distance = Distance.DOT)),
            shardNumber = 2,
            replicationFactor = 1,
        )

        assertEquals(2, Commands.targetConfigurationOf(source, null, null).shardNumber)
        assertEquals(1, Commands.targetConfigurationOf(source, null, null).replicationFactor)
        assertEquals(8, Commands.targetConfigurationOf(source, shards = 8, replicas = null).shardNumber)
        assertEquals(3, Commands.targetConfigurationOf(source, shards = null, replicas = 3).replicationFactor)
    }

    @Test
    fun `a source with no vectors at all produces no vectors rather than a guess`() {
        val target = Commands.targetConfigurationOf(CollectionParams(), null, null)

        assertNull(target.vectors)
        assertNull(target.sparseVectors)
    }
}
