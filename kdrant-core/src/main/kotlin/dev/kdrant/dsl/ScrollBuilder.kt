package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Filter
import dev.kdrant.model.PointId
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.WithPayload

/** DSL for `scroll`. The page size is fixed by the `scroll(pageSize = ...)` argument. */
@KdrantDsl
public class ScrollBuilder internal constructor(private val pageSize: Int) {
    private var filter: Filter? = null

    /** Which payload to return (defaults to the server's behavior). */
    public var withPayload: WithPayload? = null

    /** Whether to return the stored vectors. */
    public var withVector: Boolean? = null

    /** Restrict the scroll to points matching this filter. */
    public fun filter(configure: FilterBuilder.() -> Unit) {
        filter = FilterBuilder().apply(configure).build()
    }

    internal fun build(offset: PointId?): ScrollRequest = ScrollRequest(
        filter = filter,
        limit = pageSize,
        offset = offset,
        withPayload = withPayload,
        withVector = withVector,
    )
}
