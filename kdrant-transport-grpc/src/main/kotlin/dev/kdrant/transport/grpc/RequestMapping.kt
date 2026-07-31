package dev.kdrant.transport.grpc

import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Direction
import dev.kdrant.model.OrderBy
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchParams
import dev.kdrant.model.ShardKey
import dev.kdrant.model.WithPayload
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import qdrant.Collections
import qdrant.Points

/**
 * The small request shapes shared across the seam's operations: what to return, which points to act
 * on, which shards to read, and how to order a scroll.
 *
 * These are where REST's "one field, several JSON shapes" style meets protobuf's "one message per
 * shape". `with_payload` is `true` or a list of names or an exclusion object in JSON, and three
 * distinct wire variants here; `points` is an id array or a filter object, and a oneof here.
 */
internal object RequestMapping {

    /**
     * `null` means the caller did not choose, and the field is left off the request so Qdrant applies
     * its own default. Sending `enable = false` instead is not the same request: a scroll and a
     * retrieve default `with_payload` to **true**, so an explicit false would silently drop the payload
     * from every call that did not ask for one. The REST engine omits the field for the same reason,
     * and this is the difference the shared client contract caught.
     */
    fun withPayload(selector: WithPayload?): Points.WithPayloadSelector? = selector?.let {
        Points.WithPayloadSelector.newBuilder().apply {
            when (it) {
                WithPayload.None -> enable = false
                WithPayload.All -> enable = true
                is WithPayload.Include -> include = Points.PayloadIncludeSelector.newBuilder()
                    .addAllFields(it.fields)
                    .build()
                is WithPayload.Exclude -> exclude = Points.PayloadExcludeSelector.newBuilder()
                    .addAllFields(it.fields)
                    .build()
            }
        }.build()
    }

    /** As [withPayload]: `null` leaves the field off rather than sending an explicit false. */
    fun withVectors(include: Boolean?): Points.WithVectorsSelector? = include?.let {
        Points.WithVectorsSelector.newBuilder().setEnable(it).build()
    }

    fun pointsSelector(selector: DeleteSelector): Points.PointsSelector =
        Points.PointsSelector.newBuilder().apply {
            when (selector) {
                is DeleteSelector.Ids -> points = Points.PointsIdsList.newBuilder()
                    .addAllIds(selector.ids.map(PointMapping::idToProto))
                    .build()
                is DeleteSelector.ByFilter -> filter = FilterMapping.toProto(selector.filter)
            }
        }.build()

    fun shardKeySelector(key: ShardKey?): Points.ShardKeySelector? = key?.let {
        Points.ShardKeySelector.newBuilder().addShardKeys(shardKey(it)).build()
    }

    fun shardKey(key: ShardKey): Collections.ShardKey = Collections.ShardKey.newBuilder().apply {
        when (key) {
            is ShardKey.Name -> keyword = key.value
            is ShardKey.Num -> number = key.value.toLong()
        }
    }.build()

    fun shardKeyToModel(key: Collections.ShardKey): ShardKey? = when (key.keyCase) {
        Collections.ShardKey.KeyCase.KEYWORD -> ShardKey.Name(key.keyword)
        Collections.ShardKey.KeyCase.NUMBER -> ShardKey.Num(key.number.toULong())
        Collections.ShardKey.KeyCase.KEY_NOT_SET, null -> null
    }

    fun searchParams(params: SearchParams?): Points.SearchParams? = params?.let {
        Points.SearchParams.newBuilder().apply {
            it.hnswEf?.let { ef -> hnswEf = ef.toLong() }
            it.exact?.let { value -> exact = value }
            it.indexedOnly?.let { value -> indexedOnly = value }
        }.build()
    }

    fun direction(direction: Direction): Points.Direction = when (direction) {
        Direction.ASC -> Points.Direction.Asc
        Direction.DESC -> Points.Direction.Desc
    }

    fun orderBy(orderBy: OrderBy): Points.OrderBy = Points.OrderBy.newBuilder().apply {
        key = orderBy.key
        orderBy.direction?.let { direction = direction(it) }
        orderBy.startFrom?.let { startFrom = startFrom(it, orderBy.key) }
    }.build()

    fun scrollPoints(name: String, request: ScrollRequest): Points.ScrollPoints =
        Points.ScrollPoints.newBuilder().apply {
            collectionName = name
            limit = request.limit
            request.filter?.let { filter = FilterMapping.toProto(it) }
            request.offset?.let { offset = PointMapping.idToProto(it) }
            request.orderBy?.let { orderBy = RequestMapping.orderBy(it) }
            RequestMapping.shardKeySelector(request.shardKey)?.let { shardKeySelector = it }
            withPayload(request.withPayload)?.let { withPayload = it }
            withVectors(request.withVector)?.let { withVectors = it }
        }.build()

    fun pointVectors(vectors: PointVectors): Points.PointVectors = Points.PointVectors.newBuilder()
        .setId(PointMapping.idToProto(vectors.id))
        .setVectors(PointMapping.vectorsToProto(vectors.vector))
        .build()

    fun updateOperation(operation: PointsUpdateOperation): Points.PointsUpdateOperation {
        val builder = Points.PointsUpdateOperation.newBuilder()
        when (operation) {
            is PointsUpdateOperation.Upsert ->
                builder.upsert = Points.PointsUpdateOperation.PointStructList.newBuilder()
                    .addAllPoints(operation.points.map(PointMapping::pointToProto))
                    .build()
            is PointsUpdateOperation.Delete ->
                builder.deletePoints = Points.PointsUpdateOperation.DeletePoints.newBuilder()
                    .setPoints(pointsSelector(operation.selector))
                    .build()
            is PointsUpdateOperation.SetPayload ->
                builder.setPayload = Points.PointsUpdateOperation.SetPayload.newBuilder().apply {
                    putAllPayload(PayloadMapping.toProto(operation.payload))
                    pointsSelector = pointsSelector(operation.selector)
                    operation.key?.let { key = it }
                }.build()
            is PointsUpdateOperation.OverwritePayload ->
                builder.overwritePayload = Points.PointsUpdateOperation.OverwritePayload.newBuilder()
                    .putAllPayload(PayloadMapping.toProto(operation.payload))
                    .setPointsSelector(pointsSelector(operation.selector))
                    .build()
            is PointsUpdateOperation.DeletePayload ->
                builder.deletePayload = Points.PointsUpdateOperation.DeletePayload.newBuilder()
                    .addAllKeys(operation.keys)
                    .setPointsSelector(pointsSelector(operation.selector))
                    .build()
            is PointsUpdateOperation.ClearPayload ->
                builder.clearPayload = Points.PointsUpdateOperation.ClearPayload.newBuilder()
                    .setPoints(pointsSelector(operation.selector))
                    .build()
            is PointsUpdateOperation.UpdateVectors ->
                builder.updateVectors = Points.PointsUpdateOperation.UpdateVectors.newBuilder()
                    .addAllPoints(operation.points.map(::pointVectors))
                    .build()
            is PointsUpdateOperation.DeleteVectors ->
                builder.deleteVectors = Points.PointsUpdateOperation.DeleteVectors.newBuilder()
                    .setPointsSelector(pointsSelector(operation.selector))
                    .setVectors(Points.VectorsSelector.newBuilder().addAllNames(operation.vectors))
                    .build()
        }
        return builder.build()
    }

    fun fieldType(schema: PayloadSchemaType): Points.FieldType = when (schema) {
        PayloadSchemaType.KEYWORD -> Points.FieldType.FieldTypeKeyword
        PayloadSchemaType.INTEGER -> Points.FieldType.FieldTypeInteger
        PayloadSchemaType.FLOAT -> Points.FieldType.FieldTypeFloat
        PayloadSchemaType.GEO -> Points.FieldType.FieldTypeGeo
        PayloadSchemaType.TEXT -> Points.FieldType.FieldTypeText
        PayloadSchemaType.BOOL -> Points.FieldType.FieldTypeBool
        PayloadSchemaType.DATETIME -> Points.FieldType.FieldTypeDatetime
        PayloadSchemaType.UUID -> Points.FieldType.FieldTypeUuid
    }

    /**
     * `start_from` is one field of an untyped JSON value in REST and a four-way oneof here, so the
     * type has to be decided from the value rather than passed along.
     *
     * Integral before floating point, and by the text: a value too large for `Long` still parses as a
     * `Double`, so testing that first would round the boundary the caller is resuming from and skip
     * whatever fell in the gap. The same reasoning as the payload mapping, for the same reason.
     */
    private fun startFrom(value: JsonPrimitive, key: String): Points.StartFrom =
        Points.StartFrom.newBuilder().apply {
            when {
                value.isString -> datetime = value.content
                value.booleanOrNull != null -> throw IllegalArgumentException(
                    "startFrom on '$key' is a boolean, and a scroll orders by a number or a datetime",
                )
                value.longOrNull != null -> integer = value.longOrNull!!
                else -> float = value.content.toDoubleOrNull() ?: throw IllegalArgumentException(
                    "startFrom on '$key' is ${value.content}, which is neither a number nor a datetime",
                )
            }
        }.build()
}
