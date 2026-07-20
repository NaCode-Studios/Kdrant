package dev.kdrant.transport.rest

import dev.kdrant.model.AliasDescription
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.FacetHit
import dev.kdrant.model.PointGroup
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SnapshotDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Qdrant wraps every REST response in `{"result": ..., "status": ..., "time": ...}`. */

@Serializable
internal data class QueryResponse(
    @SerialName("result") val result: QueryResult,
)

@Serializable
internal data class QueryResult(
    @SerialName("points") val points: List<ScoredPoint>,
)

@Serializable
internal data class BatchQueryResponse(
    @SerialName("result") val result: List<QueryResult>,
)

@Serializable
internal data class GroupsResponse(
    @SerialName("result") val result: GroupsResultBody,
)

@Serializable
internal data class GroupsResultBody(
    @SerialName("groups") val groups: List<PointGroup>,
)

@Serializable
internal data class ScrollResponse(
    @SerialName("result") val result: ScrollPage,
)

@Serializable
internal data class ExistsResponse(
    @SerialName("result") val result: ExistsResult,
)

@Serializable
internal data class ExistsResult(
    @SerialName("exists") val exists: Boolean,
)

@Serializable
internal data class CollectionInfoResponse(
    @SerialName("result") val result: CollectionInfo,
)

@Serializable
internal data class CountResponse(
    @SerialName("result") val result: CountResult,
)

@Serializable
internal data class CountResult(
    @SerialName("count") val count: Long,
)

@Serializable
internal data class RetrieveResponse(
    @SerialName("result") val result: List<Record>,
)

@Serializable
internal data class AliasesResponse(
    @SerialName("result") val result: AliasesResult,
)

@Serializable
internal data class AliasesResult(
    @SerialName("aliases") val aliases: List<AliasDescription>,
)

@Serializable
internal data class CollectionsListResponse(
    @SerialName("result") val result: CollectionsListResult,
)

@Serializable
internal data class CollectionsListResult(
    @SerialName("collections") val collections: List<CollectionDescription>,
)

@Serializable
internal data class FacetResponse(
    @SerialName("result") val result: FacetResult,
)

@Serializable
internal data class FacetResult(
    @SerialName("hits") val hits: List<FacetHit>,
)

@Serializable
internal data class MatrixPairsResponse(
    @SerialName("result") val result: SearchMatrixPairs,
)

@Serializable
internal data class MatrixOffsetsResponse(
    @SerialName("result") val result: SearchMatrixOffsets,
)

@Serializable
internal data class SnapshotResponse(
    @SerialName("result") val result: SnapshotDescription,
)

@Serializable
internal data class SnapshotListResponse(
    @SerialName("result") val result: List<SnapshotDescription>,
)
