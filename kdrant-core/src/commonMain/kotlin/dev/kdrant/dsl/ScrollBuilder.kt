package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.Direction
import dev.kdrant.model.Filter
import dev.kdrant.model.OrderBy
import dev.kdrant.model.PointId
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.ShardKey
import dev.kdrant.model.WithPayload
import kotlinx.serialization.json.JsonPrimitive

/** DSL for `scroll`. The page size is fixed by the `scroll(pageSize = ...)` argument. */
@KdrantDsl
public class ScrollBuilder internal constructor(private val pageSize: Int) {
    private var filter: Filter? = null
    private var orderBy: OrderBy? = null

    /** Which payload to return (defaults to the server's behavior). */
    public var withPayload: WithPayload? = null

    /** Whether to return the stored vectors. */
    public var withVector: Boolean? = null

    /** Scroll only the shards holding this key. `null` (default) reads every shard. */
    public var shardKey: ShardKey? = null

    /**
     * Start the scroll at this point id, **inclusive**, so a job that was interrupted resumes where it
     * stopped instead of re-reading from the beginning. `null` (default) starts at the first point.
     *
     * Inclusive is Qdrant's own cursor semantics, not a choice made here: resuming from the last id a
     * previous run handled reads that one point again. For an idempotent consumer — anything keyed by
     * point id — that is a repeat rather than a duplicate.
     *
     * An id-ordered scroll reads in ascending id order, which is what makes an id a valid cursor at
     * all. Ignored by an ordered scroll, which resumes through [orderBy]'s `startFrom` because Qdrant
     * returns no id cursor for one.
     */
    public var startAt: PointId? = null

    /** Restrict the scroll to points matching this filter. */
    public fun filter(configure: FilterBuilder.() -> Unit) {
        filter = FilterBuilder().apply(configure).build()
    }

    /** Restrict the scroll to points matching an already-built [Filter]. */
    public fun filter(filter: Filter) {
        this.filter = filter
    }

    /**
     * Order the scroll by a numeric payload [key] instead of by point id, so a job that re-runs reads
     * the points in the same order every time. Qdrant needs a payload index on [key].
     *
     * Pass [startFrom] to resume a partially consumed scroll: it is the order value of the last point
     * the previous run handled, and it is **inclusive**, so that point is read again.
     */
    public fun orderBy(key: String, direction: Direction? = null, startFrom: Number? = null) {
        orderBy = OrderBy(key, direction, startFrom?.let { JsonPrimitive(it) })
    }

    /** As [orderBy], for a payload key holding RFC 3339 datetimes. */
    public fun orderByDatetime(key: String, direction: Direction? = null, startFrom: String? = null) {
        orderBy = OrderBy(key, direction, startFrom?.let { JsonPrimitive(it) })
    }

    internal val isOrdered: Boolean get() = orderBy != null

    /**
     * Build the request for one page. An id-ordered scroll pages through [offset]; an ordered scroll
     * pages through the order value instead, because Qdrant returns no page offset for one.
     */
    internal fun build(offset: PointId?, startFrom: JsonPrimitive? = null): ScrollRequest = ScrollRequest(
        filter = filter,
        limit = pageSize,
        offset = offset.takeIf { orderBy == null },
        withPayload = withPayload,
        withVector = withVector,
        orderBy = orderBy?.let { if (startFrom == null) it else it.copy(startFrom = startFrom) },
        shardKey = shardKey,
    )
}
