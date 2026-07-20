package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Direction
import dev.kdrant.model.Filter
import dev.kdrant.model.LookupLocation
import dev.kdrant.model.PointId
import dev.kdrant.model.Prefetch
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.SearchParams
import dev.kdrant.model.SearchRequest
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
    public fun query(vararg values: Float) { query = QueryInterface.Vector(values.toList()) }

    /** Search by the stored vector of an existing point (a "more like this" query). */
    public fun query(id: PointId) { query = QueryInterface.ById(id) }

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
        if (q is QueryInterface.Vector) require(q.values.isNotEmpty()) { "search requires a non-empty query vector" }
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
    public fun query(vararg values: Float) { query = QueryInterface.Vector(values.toList()) }

    /** Search by the stored vector of an existing point. */
    public fun query(id: PointId) { query = QueryInterface.ById(id) }

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
