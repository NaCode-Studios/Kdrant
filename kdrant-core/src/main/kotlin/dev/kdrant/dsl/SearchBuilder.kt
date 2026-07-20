package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.ContextPair
import dev.kdrant.model.Direction
import dev.kdrant.model.Filter
import dev.kdrant.model.LookupLocation
import dev.kdrant.model.PointId
import dev.kdrant.model.Prefetch
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.RecommendStrategy
import dev.kdrant.model.SearchParams
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.VectorInput
import dev.kdrant.model.WithPayload

/**
 * DSL for `search` (`/points/query`). Provide a [query] (vector, id, fusion, order-by, sample) and/or
 * one or more [prefetch] sub-requests, plus a positive [limit].
 */
@KdrantDsl
public class SearchBuilder {
    private var query: QueryInterface? = null
    private var filter: Filter? = null
    private var params: SearchParams? = null
    private var prefetch: MutableList<Prefetch>? = null
    private var lookupFrom: LookupLocation? = null

    /** Maximum number of hits to return. */
    public var limit: Int = 10

    /** Number of hits to skip (paging; costly for large offsets). */
    public var offset: Int? = null

    /** Name of the vector to search when the collection has named vectors. */
    public var using: String? = null

    /** Drop hits scoring below this threshold. */
    public var scoreThreshold: Double? = null

    /** Which payload to return (defaults to the server's behavior). */
    public var withPayload: WithPayload? = null

    /** Whether to return the stored vectors. */
    public var withVector: Boolean? = null

    /** Search by an explicit dense query vector. */
    public fun query(values: List<Float>) { query = QueryInterface.Vector(values) }

    /** Search by an explicit dense query vector. */
    public fun query(vararg values: Float) { query = QueryInterface.VectorArray(values) }

    /** Search by the stored vector of an existing point (a "more like this" query). */
    public fun query(id: PointId) { query = QueryInterface.ById(id) }

    /** Search by a sparse query vector (set [using] to the sparse vector's name). */
    public fun querySparse(indices: List<Int>, values: List<Float>) {
        query = QueryInterface.Sparse(indices, values)
    }

    /** Search by a multi-vector / late-interaction (ColBERT) query. */
    public fun queryMulti(vectors: List<List<Float>>) { query = QueryInterface.MultiVector(vectors) }

    /** Set the query directly (e.g. a prebuilt [QueryInterface]). */
    public fun query(query: QueryInterface) { this.query = query }

    /** Reciprocal Rank Fusion over the [prefetch] sources — hybrid search. */
    public fun rrf(k: Int? = null, weights: List<Float>? = null) {
        query = QueryInterface.Fusion.rrf(k, weights)
    }

    /** Distribution-Based Score Fusion over the [prefetch] sources. */
    public fun dbsf() { query = QueryInterface.Fusion.dbsf }

    /** Order points by a payload [key]. */
    public fun orderBy(key: String, direction: Direction? = null) {
        query = QueryInterface.OrderBy(key, direction)
    }

    /** Return a random sample of points. */
    public fun sample() { query = QueryInterface.Sample }

    /** Recommend points close to positive examples and far from negative ones. */
    public fun recommend(configure: RecommendBuilder.() -> Unit) {
        query = RecommendBuilder().apply(configure).build()
    }

    /** Guided (discovery) search: rank by a target, constrained by context example pairs. */
    public fun discover(configure: DiscoverBuilder.() -> Unit) {
        query = DiscoverBuilder().apply(configure).build()
    }

    /** Context search: steer results by positive/negative example pairs, without a target. */
    public fun context(configure: ContextBuilder.() -> Unit) {
        query = ContextBuilder().apply(configure).build()
    }

    /** Restrict the search to points matching this filter. */
    public fun filter(configure: FilterBuilder.() -> Unit) {
        filter = FilterBuilder().apply(configure).build()
    }

    /** Tune the accuracy/speed trade-off. */
    public fun params(configure: SearchParamsBuilder.() -> Unit) {
        params = SearchParamsBuilder().apply(configure).build()
    }

    /** Add a prefetch sub-request (repeatable; prefetches may nest for multi-stage retrieval). */
    public fun prefetch(configure: PrefetchBuilder.() -> Unit) {
        prefetch = (prefetch ?: mutableListOf()).apply { add(PrefetchBuilder().apply(configure).build()) }
    }

    /** Look up query-by-id vectors from another collection. */
    public fun lookupFrom(collection: String, vector: String? = null) {
        lookupFrom = LookupLocation(collection, vector)
    }

    internal fun build(): SearchRequest {
        val q = query
        val pf = prefetch
        require(q != null || !pf.isNullOrEmpty()) {
            "search requires a query (query(...), rrf(), orderBy(...), ...) or at least one prefetch { }"
        }
        when (q) {
            is QueryInterface.Vector ->
                require(q.values.isNotEmpty()) { "search requires a non-empty query vector" }
            is QueryInterface.VectorArray ->
                require(q.values.isNotEmpty()) { "search requires a non-empty query vector" }
            is QueryInterface.Sparse ->
                require(q.values.isNotEmpty() && q.indices.size == q.values.size) {
                    "a sparse query needs matching, non-empty indices and values"
                }
            else -> {}
        }
        require(limit > 0) { "search limit must be > 0, was $limit" }
        return SearchRequest(
            prefetch = pf,
            query = q,
            using = using,
            filter = filter,
            limit = limit,
            offset = offset,
            withPayload = withPayload,
            withVector = withVector,
            scoreThreshold = scoreThreshold,
            params = params,
            lookupFrom = lookupFrom,
        )
    }
}

/**
 * DSL for a [Prefetch] sub-request. Like [SearchBuilder] but without response-shaping options
 * (`offset`, `with_payload`, `with_vector`), which apply only to the outer request.
 */
@KdrantDsl
public class PrefetchBuilder {
    private var query: QueryInterface? = null
    private var filter: Filter? = null
    private var params: SearchParams? = null
    private var prefetch: MutableList<Prefetch>? = null
    private var lookupFrom: LookupLocation? = null

    /** Maximum number of candidates this prefetch contributes. */
    public var limit: Int? = null

    /** Name of the vector to search when the collection has named vectors. */
    public var using: String? = null

    /** Drop candidates scoring below this threshold. */
    public var scoreThreshold: Double? = null

    /** Search by an explicit dense query vector. */
    public fun query(values: List<Float>) { query = QueryInterface.Vector(values) }

    /** Search by an explicit dense query vector. */
    public fun query(vararg values: Float) { query = QueryInterface.VectorArray(values) }

    /** Search by the stored vector of an existing point. */
    public fun query(id: PointId) { query = QueryInterface.ById(id) }

    /** Search by a sparse query vector (set [using] to the sparse vector's name). */
    public fun querySparse(indices: List<Int>, values: List<Float>) {
        query = QueryInterface.Sparse(indices, values)
    }

    /** Search by a multi-vector / late-interaction (ColBERT) query. */
    public fun queryMulti(vectors: List<List<Float>>) { query = QueryInterface.MultiVector(vectors) }

    /** Set the query directly (e.g. a prebuilt [QueryInterface]). */
    public fun query(query: QueryInterface) { this.query = query }

    /** Restrict this prefetch to points matching the filter. */
    public fun filter(configure: FilterBuilder.() -> Unit) {
        filter = FilterBuilder().apply(configure).build()
    }

    /** Tune the accuracy/speed trade-off. */
    public fun params(configure: SearchParamsBuilder.() -> Unit) {
        params = SearchParamsBuilder().apply(configure).build()
    }

    /** Add a nested prefetch sub-request. */
    public fun prefetch(configure: PrefetchBuilder.() -> Unit) {
        prefetch = (prefetch ?: mutableListOf()).apply { add(PrefetchBuilder().apply(configure).build()) }
    }

    /** Look up query-by-id vectors from another collection. */
    public fun lookupFrom(collection: String, vector: String? = null) {
        lookupFrom = LookupLocation(collection, vector)
    }

    internal fun build(): Prefetch = Prefetch(
        prefetch = prefetch,
        query = query,
        using = using,
        filter = filter,
        limit = limit,
        scoreThreshold = scoreThreshold,
        params = params,
        lookupFrom = lookupFrom,
    )
}

/** DSL for [SearchParams]. */
@KdrantDsl
public class SearchParamsBuilder {
    public var hnswEf: Int? = null
    public var exact: Boolean? = null
    public var indexedOnly: Boolean? = null

    internal fun build(): SearchParams = SearchParams(hnswEf, exact, indexedOnly)
}

/** DSL for a recommend query: [positive] / [negative] examples plus an optional [strategy]. */
@KdrantDsl
public class RecommendBuilder {
    private val positive = mutableListOf<VectorInput>()
    private val negative = mutableListOf<VectorInput>()

    /** How positive/negative examples are combined (default: average-vector). */
    public var strategy: RecommendStrategy? = null

    /** A positive example: a dense vector. */
    public fun positive(values: List<Float>) { positive += QueryInterface.Vector(values) }

    /** A positive example: an existing point's stored vector. */
    public fun positive(id: PointId) { positive += QueryInterface.ById(id) }

    /** A positive example: any [VectorInput] (e.g. sparse). */
    public fun positive(input: VectorInput) { positive += input }

    /** A negative example: a dense vector. */
    public fun negative(values: List<Float>) { negative += QueryInterface.Vector(values) }

    /** A negative example: an existing point's stored vector. */
    public fun negative(id: PointId) { negative += QueryInterface.ById(id) }

    /** A negative example: any [VectorInput]. */
    public fun negative(input: VectorInput) { negative += input }

    internal fun build(): QueryInterface.Recommend =
        QueryInterface.Recommend(positive.toList(), negative.toList(), strategy)
}

/** DSL for a discovery query: a [target] plus [context] example pairs. */
@KdrantDsl
public class DiscoverBuilder {
    private var target: VectorInput? = null
    private val contextPairs = mutableListOf<ContextPair>()

    /** The target to rank by: a dense vector. */
    public fun target(values: List<Float>) { target = QueryInterface.Vector(values) }

    /** The target to rank by: an existing point's stored vector. */
    public fun target(id: PointId) { target = QueryInterface.ById(id) }

    /** The target to rank by: any [VectorInput]. */
    public fun target(input: VectorInput) { target = input }

    /** Add a positive/negative context pair that constrains the search region. */
    public fun context(positive: VectorInput, negative: VectorInput) {
        contextPairs += ContextPair(positive, negative)
    }

    internal fun build(): QueryInterface.Discover {
        val target = requireNotNull(target) { "discover requires a target(...)" }
        return QueryInterface.Discover(target, contextPairs.toList())
    }
}

/** DSL for a context query: [pair]s of positive/negative examples. */
@KdrantDsl
public class ContextBuilder {
    private val pairs = mutableListOf<ContextPair>()

    /** Add a positive/negative example pair. */
    public fun pair(positive: VectorInput, negative: VectorInput) {
        pairs += ContextPair(positive, negative)
    }

    internal fun build(): QueryInterface.Context = QueryInterface.Context(pairs.toList())
}

/** DSL for `searchBatch`: accumulate several searches to run in a single request. */
@KdrantDsl
public class BatchSearchBuilder {
    private val searches = mutableListOf<SearchRequest>()

    /** Add one search to the batch. */
    public fun search(configure: SearchBuilder.() -> Unit) {
        searches += SearchBuilder().apply(configure).build()
    }

    internal fun build(): List<SearchRequest> {
        require(searches.isNotEmpty()) { "searchBatch needs at least one search { }" }
        return searches.toList()
    }
}
