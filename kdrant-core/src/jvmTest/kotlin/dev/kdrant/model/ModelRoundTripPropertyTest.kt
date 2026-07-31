@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.model

import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.uLong
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Property-based round-trip tests (M22): whatever the generated value, encoding then decoding a model
 * yields an equal value. Catches serializer regressions the hand-picked examples might miss.
 */
class ModelRoundTripPropertyTest {

    // JSON has no NaN / Infinity, so restrict to finite floats (the only values Qdrant vectors carry).
    private val finiteFloat = Arb.float().filter { it.isFinite() }

    @Test
    fun `PointId round-trips through JSON`() {
        runBlocking {
            val pointIds = Arb.choice(
                Arb.uLong().map { PointId.num(it) },
                Arb.uuid().map { PointId.uuid(it.toString()) },
            )
            checkAll(pointIds) { id ->
                val encoded = KdrantJson.encodeToString(PointId.serializer(), id)
                assertEquals(id, KdrantJson.decodeFromString(PointId.serializer(), encoded))
            }
        }
    }

    @Test
    fun `Dense vectors round-trip through JSON`() {
        runBlocking {
            checkAll(Arb.list(finiteFloat, 1..64)) { values ->
                val vector: VectorData = VectorData.Dense(values)
                val encoded = KdrantJson.encodeToString(VectorData.serializer(), vector)
                assertEquals(vector, KdrantJson.decodeFromString(VectorData.serializer(), encoded))
            }
        }
    }

    @Test
    fun `Multi-vectors round-trip through JSON`() {
        runBlocking {
            checkAll(Arb.list(Arb.list(finiteFloat, 1..8), 1..8)) { rows ->
                val vector: VectorData = VectorData.MultiDense(rows)
                val encoded = KdrantJson.encodeToString(VectorData.serializer(), vector)
                assertEquals(vector, KdrantJson.decodeFromString(VectorData.serializer(), encoded))
            }
        }
    }

    @Test
    fun `Sparse vectors round-trip through JSON`() {
        runBlocking {
            val sparse = Arb.int(1..32).flatMap { size ->
                Arb.pair(Arb.list(Arb.int(0..100_000), size..size), Arb.list(finiteFloat, size..size))
            }.map { (indices, values) -> VectorData.Sparse(indices, values) }
            checkAll(sparse) { vector ->
                val encoded = KdrantJson.encodeToString(VectorData.serializer(), vector as VectorData)
                assertEquals(vector, KdrantJson.decodeFromString(VectorData.serializer(), encoded))
            }
        }
    }
}
