package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Filter
import dev.kdrant.model.SearchMatrixRequest

/**
 * DSL for the distance-matrix analytics endpoints (`searchMatrixPairs` / `searchMatrixOffsets`).
 *
 * ```kotlin
 * val pairs = qdrant.searchMatrixPairs("docs") {
 *     sample = 100
 *     limit = 5
 *     filter { must { "lang" eq "en" } }
 * }
 * ```
 */
@KdrantDsl
public class SearchMatrixBuilder {

    /** How many points to sample and compute distances within (server default 10, minimum 2). */
    public var sample: Int? = null

    /** How many nearest neighbours to keep per sampled point (server default 3, minimum 1). */
    public var limit: Int? = null

    /** Named vector to compute distances on; `null` uses the collection's default vector. */
    public var using: String? = null

    private var filter: Filter? = null

    /** Restrict the sampled points to those matching this filter. */
    public fun filter(configure: FilterBuilder.() -> Unit) {
        filter = FilterBuilder().apply(configure).build()
    }

    internal fun build(): SearchMatrixRequest {
        sample?.let { require(it >= 2) { "matrix 'sample' must be >= 2, was $it" } }
        limit?.let { require(it >= 1) { "matrix 'limit' must be >= 1, was $it" } }
        val effectiveFilter = filter?.takeIf { it.hasConditions() }
        return SearchMatrixRequest(filter = effectiveFilter, sample = sample, limit = limit, using = using)
    }
}
