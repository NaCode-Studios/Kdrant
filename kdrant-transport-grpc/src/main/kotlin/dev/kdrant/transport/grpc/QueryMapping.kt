package dev.kdrant.transport.grpc

import dev.kdrant.model.ContextPair
import dev.kdrant.model.DecayParams
import dev.kdrant.model.Expression
import dev.kdrant.model.Filter
import dev.kdrant.model.FusionAlgorithm
import dev.kdrant.model.LookupLocation
import dev.kdrant.model.Mmr
import dev.kdrant.model.Prefetch
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.RecommendStrategy
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.VectorInput
import qdrant.Common
import qdrant.Points

/**
 * The query surface: `SearchRequest` and its `QueryInterface` onto `QueryPoints` and `Query`.
 *
 * REST puts several shapes in one `query` field and tells them apart by their JSON — a bare array is a
 * dense vector, a bare number or string is a point id, an object with `indices` is sparse, an object
 * with `nearest` is the long form. Protobuf has a variant per shape, so the ambiguity that the REST
 * serializer resolves on the way out is resolved here on the way in, once, in [vectorInput].
 *
 * `RelevanceFeedbackInput`, the eleventh `Query` variant, has no model: it is newer than the client's
 * query surface and reaching it would mean adding it to the REST engine too.
 */
internal object QueryMapping {

    fun queryPoints(collection: String, request: SearchRequest): Points.QueryPoints =
        Points.QueryPoints.newBuilder().apply {
            collectionName = collection
            request.query?.let { query = query(it) }
            request.prefetch?.let { addAllPrefetch(it.map(::prefetch)) }
            request.using?.let { using = it }
            request.filter?.let { filter = FilterMapping.toProto(it) }
            request.limit?.let { limit = it.toLong() }
            request.offset?.let { offset = it.toLong() }
            request.scoreThreshold?.let { scoreThreshold = it.toFloat() }
            RequestMapping.searchParams(request.params)?.let { params = it }
            request.lookupFrom?.let { lookupFrom = lookupLocation(it) }
            RequestMapping.shardKeySelector(request.shardKey)?.let { shardKeySelector = it }
            withPayload = RequestMapping.withPayload(request.withPayload)
            withVectors = RequestMapping.withVectors(request.withVector)
        }.build()

    fun queryGroups(collection: String, request: SearchGroupsRequest): Points.QueryPointGroups =
        Points.QueryPointGroups.newBuilder().apply {
            collectionName = collection
            groupBy = request.groupBy
            request.query?.let { query = query(it) }
            request.prefetch?.let { addAllPrefetch(it.map(::prefetch)) }
            request.using?.let { using = it }
            request.filter?.let { filter = FilterMapping.toProto(it) }
            request.limit?.let { limit = it.toLong() }
            request.groupSize?.let { groupSize = it.toLong() }
            request.scoreThreshold?.let { scoreThreshold = it.toFloat() }
            RequestMapping.searchParams(request.params)?.let { params = it }
            request.lookupFrom?.let { lookupFrom = lookupLocation(it) }
            withPayload = RequestMapping.withPayload(request.withPayload)
            withVectors = RequestMapping.withVectors(request.withVector)
        }.build()

    fun searchMatrix(collection: String, request: SearchMatrixRequest): Points.SearchMatrixPoints =
        Points.SearchMatrixPoints.newBuilder().apply {
            collectionName = collection
            request.filter?.let { filter = FilterMapping.toProto(it) }
            request.sample?.let { sample = it.toLong() }
            request.limit?.let { limit = it.toLong() }
            request.using?.let { using = it }
        }.build()

    private fun prefetch(prefetch: Prefetch): Points.PrefetchQuery =
        Points.PrefetchQuery.newBuilder().apply {
            prefetch.query?.let { query = query(it) }
            prefetch.prefetch?.let { addAllPrefetch(it.map(::prefetch)) }
            prefetch.using?.let { using = it }
            prefetch.filter?.let { filter = FilterMapping.toProto(it) }
            prefetch.limit?.let { limit = it.toLong() }
            prefetch.scoreThreshold?.let { scoreThreshold = it.toFloat() }
            RequestMapping.searchParams(prefetch.params)?.let { params = it }
            prefetch.lookupFrom?.let { lookupFrom = lookupLocation(it) }
        }.build()

    private fun lookupLocation(location: LookupLocation): Points.LookupLocation =
        Points.LookupLocation.newBuilder().apply {
            collectionName = location.collection
            location.vector?.let { vectorName = it }
        }.build()

    private fun query(query: QueryInterface): Points.Query {
        val builder = Points.Query.newBuilder()
        when (query) {
            is VectorInput -> builder.nearest = vectorInput(query)
            is QueryInterface.Nearest -> builder.setNearest(query)
            is QueryInterface.Fusion -> builder.setFusion(query)
            is QueryInterface.OrderBy -> builder.orderBy = orderBy(query)
            QueryInterface.Sample -> builder.sample = Points.Sample.Random
            is QueryInterface.Formula -> builder.formula = formula(query)
            is QueryInterface.Recommend -> builder.recommend = recommend(query)
            is QueryInterface.Discover -> builder.discover = Points.DiscoverInput.newBuilder()
                .setTarget(vectorInput(query.target))
                .setContext(contextInput(query.context))
                .build()
            is QueryInterface.Context -> builder.context = contextInput(query.pairs)
        }
        return builder.build()
    }

    private fun orderBy(query: QueryInterface.OrderBy): Points.OrderBy =
        Points.OrderBy.newBuilder().apply {
            key = query.key
            query.direction?.let { direction = RequestMapping.direction(it) }
        }.build()

    private fun recommend(query: QueryInterface.Recommend): Points.RecommendInput =
        Points.RecommendInput.newBuilder().apply {
            addAllPositive(query.positive.map(::vectorInput))
            addAllNegative(query.negative.map(::vectorInput))
            query.strategy?.let { strategy = strategy(it) }
        }.build()

    /**
     * The long nearest form. Without MMR it is the same request as the bare vector, so it goes out as
     * `nearest` rather than as a `NearestInputWithMmr` carrying no MMR — those are different messages,
     * and only one of them is what a caller who wrote no reranking asked for.
     */
    private fun Points.Query.Builder.setNearest(query: QueryInterface.Nearest) {
        val input = vectorInput(query.input)
        val reranking = query.mmr
        if (reranking == null) {
            nearest = input
        } else {
            nearestWithMmr = Points.NearestInputWithMmr.newBuilder()
                .setNearest(input)
                .setMmr(mmr(reranking))
                .build()
        }
    }

    /**
     * Plain RRF and DBSF are values of the `Fusion` enum; RRF with a `k` or with per-source weights is
     * a separate `Rrf` message. Same distinction the REST serializer makes between `"fusion": "rrf"`
     * and an `"rrf": { ... }` object.
     */
    private fun Points.Query.Builder.setFusion(query: QueryInterface.Fusion) {
        val parameterized = query.algorithm == FusionAlgorithm.RRF &&
            (query.rrfK != null || query.rrfWeights != null)
        if (parameterized) {
            rrf = Points.Rrf.newBuilder().apply {
                query.rrfK?.let { k = it }
                query.rrfWeights?.let { addAllWeights(it) }
            }.build()
        } else {
            fusion = when (query.algorithm) {
                FusionAlgorithm.RRF -> Points.Fusion.RRF
                FusionAlgorithm.DBSF -> Points.Fusion.DBSF
            }
        }
    }

    private fun mmr(mmr: Mmr): Points.Mmr = Points.Mmr.newBuilder().apply {
        mmr.diversity?.let { diversity = it }
        mmr.candidatesLimit?.let { candidatesLimit = it }
    }.build()

    private fun contextInput(pairs: List<ContextPair>): Points.ContextInput =
        Points.ContextInput.newBuilder()
            .addAllPairs(
                pairs.map {
                    Points.ContextInputPair.newBuilder()
                        .setPositive(vectorInput(it.positive))
                        .setNegative(vectorInput(it.negative))
                        .build()
                },
            )
            .build()

    private fun strategy(strategy: RecommendStrategy): Points.RecommendStrategy = when (strategy) {
        RecommendStrategy.AVERAGE_VECTOR -> Points.RecommendStrategy.AverageVector
        RecommendStrategy.BEST_SCORE -> Points.RecommendStrategy.BestScore
        RecommendStrategy.SUM_SCORES -> Points.RecommendStrategy.SumScores
    }

    private fun vectorInput(input: VectorInput): Points.VectorInput =
        Points.VectorInput.newBuilder().apply {
            when (input) {
                is QueryInterface.Vector -> dense = denseOf(input.values)
                is QueryInterface.VectorArray -> dense = denseOf(input.values.asList())
                is QueryInterface.ById -> id = PointMapping.idToProto(input.id)
                is QueryInterface.Sparse -> sparse = Points.SparseVector.newBuilder()
                    .addAllIndices(input.indices)
                    .addAllValues(input.values)
                    .build()
                is QueryInterface.MultiVector -> multiDense = Points.MultiDenseVector.newBuilder()
                    .addAllVectors(input.vectors.map(::denseOf))
                    .build()
            }
        }.build()

    private fun denseOf(values: List<Float>): Points.DenseVector =
        Points.DenseVector.newBuilder().addAllData(values).build()

    private fun formula(formula: QueryInterface.Formula): Points.Formula =
        Points.Formula.newBuilder()
            .setExpression(expression(formula.formula))
            .putAllDefaults(formula.defaults.mapValues { (_, value) -> PayloadMapping.valueToProto(value) })
            .build()

    private fun expression(expression: Expression): Points.Expression {
        val builder = Points.Expression.newBuilder()
        when (expression) {
            is Expression.Value -> builder.constant = expression.value.toFloat()
            is Expression.Variable -> builder.variable = expression.name
            is Expression.Condition -> builder.condition = conditionOf(expression)
            is Expression.GeoDistance -> builder.geoDistance = geoDistance(expression)
            is Expression.Datetime -> builder.datetime = expression.value
            is Expression.DatetimeKey -> builder.datetimeKey = expression.key
            is Expression.Mult -> builder.mult = Points.MultExpression.newBuilder()
                .addAllMult(expression.operands.map(::expression))
                .build()
            is Expression.Sum -> builder.sum = Points.SumExpression.newBuilder()
                .addAllSum(expression.operands.map(::expression))
                .build()
            is Expression.Div -> builder.div = div(expression)
            is Expression.Pow -> builder.pow = Points.PowExpression.newBuilder()
                .setBase(expression(expression.base))
                .setExponent(expression(expression.exponent))
                .build()
            is Expression.Neg -> builder.neg = expression(expression.operand)
            is Expression.Abs -> builder.abs = expression(expression.operand)
            is Expression.Sqrt -> builder.sqrt = expression(expression.operand)
            is Expression.Exp -> builder.exp = expression(expression.operand)
            is Expression.Log10 -> builder.log10 = expression(expression.operand)
            is Expression.Ln -> builder.ln = expression(expression.operand)
            is Expression.ExpDecay -> builder.expDecay = decay(expression.params)
            is Expression.GaussDecay -> builder.gaussDecay = decay(expression.params)
            is Expression.LinDecay -> builder.linDecay = decay(expression.params)
        }
        return builder.build()
    }

    private fun geoDistance(expression: Expression.GeoDistance): Points.GeoDistance =
        Points.GeoDistance.newBuilder()
            .setOrigin(
                Common.GeoPoint.newBuilder()
                    .setLon(expression.origin.lon)
                    .setLat(expression.origin.lat),
            )
            .setTo(expression.to)
            .build()

    private fun div(expression: Expression.Div): Points.DivExpression =
        Points.DivExpression.newBuilder().apply {
            left = expression(expression.left)
            right = expression(expression.right)
            expression.byZeroDefault?.let { byZeroDefault = it.toFloat() }
        }.build()

    /**
     * A filter condition used as a number: true is 1.0 and false is 0.0. The wire field is a bare
     * `Condition`, so the condition is wrapped in a one-clause filter and unwrapped again, which is
     * the only way to reach [FilterMapping]'s per-condition mapping from outside it.
     */
    private fun conditionOf(expression: Expression.Condition): Common.Condition =
        FilterMapping.toProto(Filter(must = listOf(expression.condition))).getMust(0)

    private fun decay(params: DecayParams): Points.DecayParamsExpression =
        Points.DecayParamsExpression.newBuilder().apply {
            x = expression(params.x)
            params.target?.let { target = expression(it) }
            params.scale?.let { scale = it.toFloat() }
            params.midpoint?.let { midpoint = it.toFloat() }
        }.build()
}
