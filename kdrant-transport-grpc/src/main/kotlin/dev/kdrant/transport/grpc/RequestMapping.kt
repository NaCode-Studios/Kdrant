package dev.kdrant.transport.grpc

import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Direction
import dev.kdrant.model.OrderBy
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchParams
import dev.kdrant.model.ShardKey
import dev.kdrant.model.Tokenizer
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
     * The index type carried by [params], so the gRPC request names it the way the REST body's
     * `type` discriminator does.
     */
    fun fieldType(params: PayloadIndexParams): Points.FieldType = when (params) {
        is PayloadIndexParams.Keyword -> Points.FieldType.FieldTypeKeyword
        is PayloadIndexParams.Integer -> Points.FieldType.FieldTypeInteger
        is PayloadIndexParams.Float -> Points.FieldType.FieldTypeFloat
        is PayloadIndexParams.Geo -> Points.FieldType.FieldTypeGeo
        is PayloadIndexParams.Text -> Points.FieldType.FieldTypeText
        is PayloadIndexParams.Bool -> Points.FieldType.FieldTypeBool
        is PayloadIndexParams.Datetime -> Points.FieldType.FieldTypeDatetime
        is PayloadIndexParams.Uuid -> Points.FieldType.FieldTypeUuid
    }

    /**
     * The index parameters as the protobuf oneof. Every field is optional on both sides, so an unset
     * Kotlin `null` stays unset here rather than being sent as a default the server would then apply.
     */
    fun indexParams(params: PayloadIndexParams): Collections.PayloadIndexParams =
        Collections.PayloadIndexParams.newBuilder().apply {
            when (params) {
                is PayloadIndexParams.Keyword -> keywordIndexParams = keyword(params)
                is PayloadIndexParams.Integer -> integerIndexParams = integer(params)
                is PayloadIndexParams.Float -> floatIndexParams = float(params)
                is PayloadIndexParams.Geo -> geoIndexParams = geo(params)
                is PayloadIndexParams.Text -> textIndexParams = text(params)
                is PayloadIndexParams.Bool -> boolIndexParams = bool(params)
                is PayloadIndexParams.Datetime -> datetimeIndexParams = datetime(params)
                is PayloadIndexParams.Uuid -> uuidIndexParams = uuid(params)
            }
        }.build()

    private fun keyword(params: PayloadIndexParams.Keyword): Collections.KeywordIndexParams =
        Collections.KeywordIndexParams.newBuilder().apply {
            params.isTenant?.let { isTenant = it }
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun integer(params: PayloadIndexParams.Integer): Collections.IntegerIndexParams =
        Collections.IntegerIndexParams.newBuilder().apply {
            params.lookup?.let { lookup = it }
            params.range?.let { range = it }
            params.isPrincipal?.let { isPrincipal = it }
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun float(params: PayloadIndexParams.Float): Collections.FloatIndexParams =
        Collections.FloatIndexParams.newBuilder().apply {
            params.isPrincipal?.let { isPrincipal = it }
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun geo(params: PayloadIndexParams.Geo): Collections.GeoIndexParams =
        Collections.GeoIndexParams.newBuilder().apply {
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun text(params: PayloadIndexParams.Text): Collections.TextIndexParams =
        Collections.TextIndexParams.newBuilder().apply {
            // The tokenizer is a plain field rather than an optional one, and its zero value is
            // `Unknown`, which the server rejects. Leaving it unset therefore means asking for the
            // server's own default explicitly.
            tokenizer = tokenizer(params.tokenizer)
            params.lowercase?.let { lowercase = it }
            params.minTokenLen?.let { minTokenLen = it.toLong() }
            params.maxTokenLen?.let { maxTokenLen = it.toLong() }
            params.phraseMatching?.let { phraseMatching = it }
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun bool(params: PayloadIndexParams.Bool): Collections.BoolIndexParams =
        Collections.BoolIndexParams.newBuilder().apply {
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun datetime(params: PayloadIndexParams.Datetime): Collections.DatetimeIndexParams =
        Collections.DatetimeIndexParams.newBuilder().apply {
            params.isPrincipal?.let { isPrincipal = it }
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun uuid(params: PayloadIndexParams.Uuid): Collections.UuidIndexParams =
        Collections.UuidIndexParams.newBuilder().apply {
            params.isTenant?.let { isTenant = it }
            params.onDisk?.let { onDisk = it }
        }.build()

    private fun tokenizer(value: Tokenizer?): Collections.TokenizerType = when (value) {
        null -> Collections.TokenizerType.Word
        Tokenizer.PREFIX -> Collections.TokenizerType.Prefix
        Tokenizer.WHITESPACE -> Collections.TokenizerType.Whitespace
        Tokenizer.WORD -> Collections.TokenizerType.Word
        Tokenizer.MULTILINGUAL -> Collections.TokenizerType.Multilingual
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
