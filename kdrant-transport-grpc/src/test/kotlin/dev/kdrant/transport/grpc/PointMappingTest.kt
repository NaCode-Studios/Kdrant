package dev.kdrant.transport.grpc

import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.VectorData
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import qdrant.Points

class PointMappingTest {

    @Test
    fun `a numeric id above Long MAX still round-trips, because the wire field is unsigned`() {
        val id = PointId.num(ULong.MAX_VALUE)

        val proto = PointMapping.idToProto(id)

        // As a signed Long this is -1; as the uint64 the proto declares it is ULong.MAX_VALUE, and
        // reading it back through the model is what proves the reinterpretation is symmetric.
        assertEquals(-1L, proto.num)
        assertEquals(id, PointMapping.idToModel(proto))
    }

    @Test
    fun `a uuid id round-trips`() {
        val id = PointId.uuid("550e8400-e29b-41d4-a716-446655440000")

        assertEquals(id, PointMapping.idToModel(PointMapping.idToProto(id)))
    }

    @Test
    fun `an id Qdrant sent with no variant set is a broken response, not a silent default`() {
        val error = assertThrows(IllegalStateException::class.java) {
            PointMapping.idToModel(qdrant.Common.PointId.newBuilder().build())
        }
        assertTrue(error.message!!.contains("no id"), error.message)
    }

    @Test
    fun `a dense vector maps to the dense variant, whether it came boxed or as a FloatArray`() {
        val boxed = PointMapping.vectorsToProto(VectorData.Dense(listOf(0.1f, 0.2f)))
        val array = PointMapping.vectorsToProto(VectorData.DenseArray(floatArrayOf(0.1f, 0.2f)))

        assertEquals(Points.Vector.VectorCase.DENSE, boxed.vector.vectorCase)
        assertEquals(listOf(0.1f, 0.2f), boxed.vector.dense.dataList)
        assertEquals(boxed, array, "the no-boxing path must produce the same message")
    }

    @Test
    fun `a sparse vector carries its indices and values`() {
        val proto = PointMapping.vectorsToProto(VectorData.Sparse(listOf(1, 7), listOf(0.5f, 0.25f)))

        assertEquals(Points.Vector.VectorCase.SPARSE, proto.vector.vectorCase)
        assertEquals(listOf(1, 7), proto.vector.sparse.indicesList)
        assertEquals(listOf(0.5f, 0.25f), proto.vector.sparse.valuesList)
    }

    @Test
    fun `a multi-dense vector keeps one dense message per row`() {
        val proto = PointMapping.vectorsToProto(
            VectorData.MultiDense(listOf(listOf(0.1f, 0.2f), listOf(0.3f, 0.4f))),
        )

        assertEquals(2, proto.vector.multiDense.vectorsCount)
        assertEquals(listOf(0.3f, 0.4f), proto.vector.multiDense.getVectors(1).dataList)
    }

    @Test
    fun `named vectors become the map form, and each entry keeps its own kind`() {
        val proto = PointMapping.vectorsToProto(
            VectorData.Named(
                mapOf(
                    "text" to VectorData.Dense(listOf(0.1f)),
                    "bm25" to VectorData.Sparse(listOf(3), listOf(1.0f)),
                ),
            ),
        )

        assertEquals(Points.Vectors.VectorsOptionsCase.VECTORS, proto.vectorsOptionsCase)
        assertEquals(setOf("text", "bm25"), proto.vectors.vectorsMap.keys)
        assertEquals(Points.Vector.VectorCase.DENSE, proto.vectors.vectorsMap.getValue("text").vectorCase)
        assertEquals(Points.Vector.VectorCase.SPARSE, proto.vectors.vectorsMap.getValue("bm25").vectorCase)
    }

    @Test
    fun `a named map nested inside a named map is refused rather than flattened`() {
        val nested = VectorData.Named(mapOf("outer" to VectorData.Named(mapOf("inner" to VectorData.Dense(listOf(0.1f))))))

        val error = assertThrows(IllegalArgumentException::class.java) {
            PointMapping.vectorsToProto(nested)
        }
        assertTrue(error.message!!.contains("outer"), error.message)
    }

    @Test
    fun `a raw JSON vector has no protobuf equivalent and says so`() {
        val raw = VectorData.Raw(JsonPrimitive("whatever the server sent"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            PointMapping.vectorsToProto(raw)
        }
        assertTrue(error.message!!.contains("raw JSON"), error.message)
    }

    @Test
    fun `every vector kind survives a full round trip through the output message`() {
        listOf(
            VectorData.Dense(listOf(0.1f, 0.2f)),
            VectorData.Sparse(listOf(1, 7), listOf(0.5f, 0.25f)),
            VectorData.MultiDense(listOf(listOf(0.1f), listOf(0.2f))),
        ).forEach { original ->
            val sent = PointMapping.vectorsToProto(original)
            // The server answers with VectorOutput, a different message from the Vector it was sent.
            val output = Points.VectorsOutput.newBuilder()
                .setVector(
                    Points.VectorOutput.newBuilder().apply {
                        when (sent.vector.vectorCase) {
                            Points.Vector.VectorCase.DENSE -> dense = sent.vector.dense
                            Points.Vector.VectorCase.SPARSE -> sparse = sent.vector.sparse
                            Points.Vector.VectorCase.MULTI_DENSE -> multiDense = sent.vector.multiDense
                            else -> error("unexpected ${sent.vector.vectorCase}")
                        }
                    },
                )
                .build()

            assertEquals(original, PointMapping.vectorsToModel(output), "round trip of $original")
        }
    }

    @Test
    fun `a point carries its id, vector and payload together`() {
        val proto = PointMapping.pointToProto(
            PointStruct(
                id = PointId.num(7u),
                vector = VectorData.Dense(listOf(0.1f)),
                payload = buildJsonObject { put("lang", JsonPrimitive("en")) },
            ),
        )

        assertEquals(7L, proto.id.num)
        assertEquals(listOf(0.1f), proto.vectors.vector.dense.dataList)
        assertEquals("en", proto.payloadMap.getValue("lang").stringValue)
    }

    @Test
    fun `a point with no payload sends an empty map, and reads back as no payload at all`() {
        val proto = PointMapping.pointToProto(
            PointStruct(PointId.num(1u), VectorData.Dense(listOf(0.1f)), payload = null),
        )
        assertTrue(proto.payloadMap.isEmpty())

        val record = PointMapping.recordToModel(
            Points.RetrievedPoint.newBuilder().setId(PointMapping.idToProto(PointId.num(1u))).build(),
        )
        assertNull(record.payload, "an empty payload map is absence, not an empty object")
        assertNull(record.vector)
    }

    @Test
    fun `a scored point keeps its score alongside the mapped fields`() {
        val scored = PointMapping.scoredPointToModel(
            Points.ScoredPoint.newBuilder()
                .setId(PointMapping.idToProto(PointId.uuid("doc-1")))
                .setScore(0.87f)
                .putAllPayload(PayloadMapping.toProto(buildJsonObject { put("t", JsonPrimitive("x")) }))
                .build(),
        )

        assertEquals(PointId.uuid("doc-1"), scored.id)
        assertEquals(0.87f, scored.score)
        assertEquals("x", (scored.payload!!["t"] as JsonPrimitive).content)
    }
}
