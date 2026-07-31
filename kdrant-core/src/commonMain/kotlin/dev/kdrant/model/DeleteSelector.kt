package dev.kdrant.model

/** Selects which points a `delete` targets: an explicit id list or a filter. */
public sealed interface DeleteSelector {

    /** Delete the given point ids. */
    public data class Ids(public val ids: List<PointId>) : DeleteSelector

    /** Delete every point matching the filter. */
    public data class ByFilter(public val filter: Filter) : DeleteSelector
}
