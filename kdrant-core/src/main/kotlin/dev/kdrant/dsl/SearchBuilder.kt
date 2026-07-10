package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Filter
import dev.kdrant.model.SearchParams
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.WithPayload

/** DSL for `search`. A query vector and a positive [limit] are required. */
@KdrantDsl
public class SearchBuilder {
    private var query: List<Float>? = null
    private var filter: Filter? = null
    private var params: SearchParams? = null

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

    /** The query vector. */
    public fun query(values: List<Float>) {
        query = values
    }

    /** The query vector. */
    public fun query(vararg values: Float) {
        query = values.toList()
    }

    /** Restrict the search to points matching this filter. */
    public fun filter(configure: FilterBuilder.() -> Unit) {
        filter = FilterBuilder().apply(configure).build()
    }

    /** Tune the accuracy/speed trade-off. */
    public fun params(configure: SearchParamsBuilder.() -> Unit) {
        params = SearchParamsBuilder().apply(configure).build()
    }

    internal fun build(): SearchRequest {
        val query = requireNotNull(query) { "search requires a query vector; call query(...)" }
        require(query.isNotEmpty()) { "search requires a non-empty query vector" }
        require(limit > 0) { "search limit must be > 0, was $limit" }
        return SearchRequest(
            query = query,
            using = using,
            filter = filter,
            limit = limit,
            offset = offset,
            withPayload = withPayload,
            withVector = withVector,
            scoreThreshold = scoreThreshold,
            params = params,
        )
    }
}

/** DSL for [SearchParams]. */
@KdrantDsl
public class SearchParamsBuilder {
    public var hnswEf: Int? = null
    public var exact: Boolean? = null
    public var indexedOnly: Boolean? = null

    internal fun build(): SearchParams = SearchParams(hnswEf, exact, indexedOnly)
}
