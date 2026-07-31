package dev.kdrant.transport.grpc

import dev.kdrant.model.FacetValue
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.VectorData
import kotlinx.serialization.json.JsonPrimitive
import qdrant.Common
import qdrant.Points

/**
 * Point, id and vector conversion between Kdrant's models and Qdrant's protobuf messages.
 *
 * Two shapes here do not line up with the REST models, and both are load-bearing.
 *
 * A named vector map on the wire is `map<string, Vector>`, and each entry is a whole `Vector` with its
 * own dense, sparse or multi variant. Kdrant's [VectorData.Named] holds the same thing recursively, so
 * a nested `Named` inside a `Named` has no wire form and is rejected rather than flattened into
 * something that would store a different collection than the caller described.
 *
 * Reads come back as `VectorOutput`, a separate message from `Vector` with the inference variants
 * removed: a server never echoes back "embed this document for me", it returns what it stored.
 */
internal object PointMapping {

    fun idToProto(id: PointId): Common.PointId = Common.PointId.newBuilder().apply {
        when (id) {
            is PointId.Num -> num = id.value.toLong()
            is PointId.Uuid -> uuid = id.value
        }
    }.build()

    fun idToModel(id: Common.PointId): PointId = when (id.pointIdOptionsCase) {
        Common.PointId.PointIdOptionsCase.NUM -> PointId.num(id.num.toULong())
        Common.PointId.PointIdOptionsCase.UUID -> PointId.uuid(id.uuid)
        Common.PointId.PointIdOptionsCase.POINTIDOPTIONS_NOT_SET, null ->
            error("Qdrant returned a point with no id set")
    }

    fun pointToProto(point: PointStruct): Points.PointStruct = Points.PointStruct.newBuilder()
        .setId(idToProto(point.id))
        .setVectors(vectorsToProto(point.vector))
        .putAllPayload(PayloadMapping.toProto(point.payload ?: emptyMapPayload()))
        .build()

    private fun emptyMapPayload() = kotlinx.serialization.json.JsonObject(emptyMap())

    /** The `Vectors` wrapper: one anonymous vector, or a map of named ones. */
    fun vectorsToProto(data: VectorData): Points.Vectors = Points.Vectors.newBuilder().apply {
        if (data is VectorData.Named) {
            vectors = Points.NamedVectors.newBuilder()
                .putAllVectors(data.vectors.mapValues { (name, nested) -> vectorToProto(nested, name) })
                .build()
        } else {
            vector = vectorToProto(data, name = null)
        }
    }.build()

    private fun vectorToProto(data: VectorData, name: String?): Points.Vector =
        Points.Vector.newBuilder().apply {
            when (data) {
                is VectorData.Dense -> dense = denseOf(data.values)
                is VectorData.DenseArray -> dense = denseOf(data.values.asList())
                is VectorData.MultiDense -> multiDense = Points.MultiDenseVector.newBuilder()
                    .addAllVectors(data.vectors.map { denseOf(it) })
                    .build()
                is VectorData.Sparse -> sparse = Points.SparseVector.newBuilder()
                    .addAllValues(data.values.map { it })
                    .addAllIndices(data.indices)
                    .build()
                is VectorData.Named -> throw IllegalArgumentException(
                    "a named vector map cannot hold another named map; ${name ?: "the anonymous vector"} " +
                        "nests one, and Qdrant's wire format has no shape for that",
                )
                is VectorData.Raw -> throw IllegalArgumentException(
                    "the gRPC engine cannot send a raw JSON vector; it exists so the REST engine can " +
                        "carry a shape this client does not model, and protobuf has no equivalent",
                )
            }
        }.build()

    private fun denseOf(values: List<Float>): Points.DenseVector =
        Points.DenseVector.newBuilder().addAllData(values).build()

    fun vectorsToModel(vectors: Points.VectorsOutput): VectorData? =
        when (vectors.vectorsOptionsCase) {
            Points.VectorsOutput.VectorsOptionsCase.VECTOR -> vectorToModel(vectors.vector)
            Points.VectorsOutput.VectorsOptionsCase.VECTORS -> VectorData.Named(
                vectors.vectors.vectorsMap.mapValues { (_, v) -> vectorToModel(v) ?: VectorData.Dense(emptyList()) },
            )
            Points.VectorsOutput.VectorsOptionsCase.VECTORSOPTIONS_NOT_SET, null -> null
        }

    private fun vectorToModel(vector: Points.VectorOutput): VectorData? =
        when (vector.vectorCase) {
            Points.VectorOutput.VectorCase.DENSE -> VectorData.Dense(vector.dense.dataList)
            Points.VectorOutput.VectorCase.SPARSE -> VectorData.Sparse(
                indices = vector.sparse.indicesList.map { it },
                values = vector.sparse.valuesList,
            )
            Points.VectorOutput.VectorCase.MULTI_DENSE ->
                VectorData.MultiDense(vector.multiDense.vectorsList.map { it.dataList })
            // A point stored without this vector, which is normal for a named-vector collection where
            // only some vectors were written.
            Points.VectorOutput.VectorCase.VECTOR_NOT_SET, null -> null
        }

    fun recordToModel(point: Points.RetrievedPoint): Record = Record(
        id = idToModel(point.id),
        payload = PayloadMapping.toJson(point.payloadMap).takeIf { point.payloadMap.isNotEmpty() },
        vector = if (point.hasVectors()) vectorsToModel(point.vectors) else null,
        orderValue = if (point.hasOrderValue()) orderValueToModel(point.orderValue) else null,
    )

    /**
     * The value an ordered scroll sorted this point by. It is what the client pages on — Qdrant returns
     * no cursor for an ordered scroll — so dropping it here would silently turn a multi-page ordered
     * scroll into its first page.
     */
    private fun orderValueToModel(value: Points.OrderValue): JsonPrimitive? = when (value.variantCase) {
        Points.OrderValue.VariantCase.INT -> JsonPrimitive(value.int)
        Points.OrderValue.VariantCase.FLOAT -> JsonPrimitive(value.float)
        Points.OrderValue.VariantCase.VARIANT_NOT_SET, null -> null
    }

    fun groupId(id: Points.GroupId): JsonPrimitive = when (id.kindCase) {
        Points.GroupId.KindCase.STRING_VALUE -> JsonPrimitive(id.stringValue)
        Points.GroupId.KindCase.INTEGER_VALUE -> JsonPrimitive(id.integerValue)
        Points.GroupId.KindCase.UNSIGNED_VALUE -> JsonPrimitive(id.unsignedValue.toULong().toString())
        Points.GroupId.KindCase.KIND_NOT_SET, null -> JsonPrimitive("")
    }

    fun facetValue(value: Points.FacetValue): FacetValue = when (value.variantCase) {
        Points.FacetValue.VariantCase.STRING_VALUE -> FacetValue.StringValue(value.stringValue)
        Points.FacetValue.VariantCase.INTEGER_VALUE -> FacetValue.IntValue(value.integerValue)
        Points.FacetValue.VariantCase.BOOL_VALUE -> FacetValue.BoolValue(value.boolValue)
        Points.FacetValue.VariantCase.VARIANT_NOT_SET, null -> FacetValue.StringValue("")
    }

    fun scoredPointToModel(point: Points.ScoredPoint): ScoredPoint = ScoredPoint(
        id = idToModel(point.id),
        score = point.score,
        payload = PayloadMapping.toJson(point.payloadMap).takeIf { point.payloadMap.isNotEmpty() },
        vector = if (point.hasVectors()) vectorsToModel(point.vectors) else null,
    )
}
