package dev.kdrant.transport.grpc

import dev.kdrant.dsl.filter
import dev.kdrant.dsl.payloadOf
import dev.kdrant.kdrantConfig
import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Direction
import dev.kdrant.model.Distance
import dev.kdrant.model.FacetValue
import dev.kdrant.model.OrderBy
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation
import dev.kdrant.model.QueryInterface
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.Tokenizer
import dev.kdrant.model.VectorData
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import dev.kdrant.model.WithPayload
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import qdrant.Collections
import qdrant.CollectionsGrpcKt
import qdrant.Points
import qdrant.PointsGrpcKt
import qdrant.SnapshotsGrpcKt
import qdrant.SnapshotsService

/**
 * What the transport puts on the wire, asserted against a real gRPC server over the in-process
 * transport. The fake services record every request and answer with canned responses, so both halves
 * of each operation are covered: the message built going out, and the model read coming back.
 *
 * This is the gRPC counterpart of the REST engine's `MockEngine` tests. It is not the client contract —
 * that runs against a real Qdrant and lives in `kdrant-testkit` — it is the layer below, where a field
 * set on the wrong message is visible without a server that has to agree.
 */
class GrpcQdrantTransportTest {

    private lateinit var server: Server
    private lateinit var transport: GrpcQdrantTransport
    private val points = RecordingPoints()
    private val collections = RecordingCollections()
    private val snapshots = RecordingSnapshots()

    @BeforeEach
    fun start() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(points)
            .addService(collections)
            .addService(snapshots)
            .build()
            .start()
        transport = GrpcQdrantTransport(
            config = kdrantConfig("localhost", 6334),
            channel = InProcessChannelBuilder.forName(name).directExecutor().build(),
            upsertBatchSize = 2,
        )
    }

    @AfterEach
    fun stop() {
        transport.close()
        server.shutdownNow()
    }

    // --- Collections -------------------------------------------------------------------------

    @Test
    fun `createCollection carries the vector config it was given`() = runTest {
        transport.createCollection(
            "docs",
            CreateCollectionRequest(
                vectors = VectorsConfig.Named(mapOf("text" to VectorParams(size = 8, distance = Distance.DOT))),
                onDiskPayload = true,
                shardNumber = 3,
            ),
        )

        val request = collections.created.single()
        assertEquals("docs", request.collectionName)
        assertEquals(true, request.onDiskPayload)
        assertEquals(3, request.shardNumber)
        val params = request.vectorsConfig.paramsMap.mapMap.getValue("text")
        assertEquals(8L, params.size)
        assertEquals(Collections.Distance.Dot, params.distance)
    }

    @Test
    fun `getCollection reads the status, counts and config back`() = runTest {
        val info = transport.getCollection("docs")

        assertEquals(CollectionStatus.GREEN, info.status)
        assertEquals(7L, info.pointsCount)
        assertEquals(
            VectorsConfig.Single(VectorParams(size = 4, distance = Distance.COSINE)),
            info.config?.params?.vectors,
        )
        assertEquals("keyword", info.payloadSchema.getValue("lang").dataType)
        assertEquals(PayloadSchemaType.KEYWORD, info.payloadSchema.getValue("lang").schemaType)
    }

    @Test
    fun `collectionExists reads the nested boolean rather than the envelope`() = runTest {
        assertTrue(transport.collectionExists("docs"))
    }

    // --- Points ------------------------------------------------------------------------------

    @Test
    fun `a list longer than the batch size is split, and every point is sent once`() = runTest {
        val batch = (1L..5L).map { PointStruct(PointId.num(it), VectorData.Dense(listOf(0.1f))) }

        transport.upsert("docs", batch, wait = true)

        // upsertBatchSize is 2, so five points are three requests: 2, 2, 1.
        assertEquals(listOf(2, 2, 1), points.upserts.map { it.pointsCount })
        assertEquals((1L..5L).toList(), points.upserts.flatMap { it.pointsList.map { p -> p.id.num } })
        assertTrue(points.upserts.all { it.wait })
    }

    @Test
    fun `a flow is chunked the same way a list is`() = runTest {
        val batch = (1L..3L).map { PointStruct(PointId.num(it), VectorData.Dense(listOf(0.1f))) }

        transport.upsert("docs", flowOf(*batch.toTypedArray()), wait = false)

        assertEquals(listOf(2, 1), points.upserts.map { it.pointsCount })
        assertFalse(points.upserts.first().wait)
    }

    @Test
    fun `retrieve asks for the payload and vectors it was told to, and reads the points back`() = runTest {
        val records = transport.retrieve(
            "docs",
            ids = listOf(PointId.num(1), PointId.uuid(UUID)),
            withPayload = WithPayload.include("lang"),
            withVector = true,
        )

        val request = points.gets.single()
        assertEquals(listOf(1L), request.idsList.filter { it.hasNum() }.map { it.num })
        assertEquals(listOf(UUID), request.idsList.filter { !it.hasNum() }.map { it.uuid })
        assertEquals(listOf("lang"), request.withPayload.include.fieldsList)
        assertTrue(request.withVectors.enable)

        assertEquals(PointId.num(1), records.single().id)
        assertEquals(JsonPrimitive("it"), records.single().payload?.get("lang"))
    }

    @Test
    fun `count sends the filter and reads the nested result`() = runTest {
        val total = transport.count("docs", filter { must { "lang" eq "it" } }, exact = true)

        assertEquals(42L, total)
        val request = points.counts.single()
        assertTrue(request.exact)
        assertEquals("lang", request.filter.mustList.single().field.key)
    }

    @Test
    fun `delete by id and delete by filter reach different halves of the selector`() = runTest {
        transport.delete("docs", DeleteSelector.Ids(listOf(PointId.num(1))), wait = true)
        transport.delete("docs", DeleteSelector.ByFilter(filter { must { "stale" eq true } }), wait = true)

        assertEquals(
            listOf(Points.PointsSelector.PointsSelectorOneOfCase.POINTS, Points.PointsSelector.PointsSelectorOneOfCase.FILTER),
            points.deletes.map { it.points.pointsSelectorOneOfCase },
        )
    }

    // --- Query -------------------------------------------------------------------------------

    @Test
    fun `a query carries its vector, limit, filter and selectors`() = runTest {
        val hits = transport.query(
            "docs",
            SearchRequest(
                query = QueryInterface.Vector(listOf(0.1f, 0.2f)),
                limit = 5,
                offset = 2,
                filter = filter { must { "lang" eq "it" } },
                withPayload = WithPayload.All,
                withVector = true,
                scoreThreshold = 0.5,
            ),
        )

        val request = points.queries.single()
        assertEquals(listOf(0.1f, 0.2f), request.query.nearest.dense.dataList)
        assertEquals(5L, request.limit)
        assertEquals(2L, request.offset)
        assertEquals(0.5f, request.scoreThreshold)
        assertTrue(request.withPayload.enable)
        assertTrue(request.withVectors.enable)
        assertEquals(PointId.num(9), hits.single().id)
        assertEquals(0.75f, hits.single().score)
    }

    @Test
    fun `a prefetch with fusion becomes a prefetch list and a fusion query`() = runTest {
        transport.query(
            "docs",
            SearchRequest(
                prefetch = listOf(
                    dev.kdrant.model.Prefetch(query = QueryInterface.Vector(listOf(0.1f)), limit = 20),
                    dev.kdrant.model.Prefetch(query = QueryInterface.Sparse(listOf(1), listOf(0.5f)), limit = 20),
                ),
                query = QueryInterface.Fusion.rrf(k = 60),
                limit = 5,
            ),
        )

        val request = points.queries.single()
        assertEquals(2, request.prefetchCount)
        assertEquals(listOf(1), request.getPrefetch(1).query.nearest.sparse.indicesList)
        // A parameterized RRF is the Rrf message, not the Fusion enum.
        assertEquals(Points.Query.VariantCase.RRF, request.query.variantCase)
        assertEquals(60, request.query.rrf.k)
    }

    @Test
    fun `a plain fusion stays the enum, so it is not confused with a parameterized one`() = runTest {
        transport.query("docs", SearchRequest(query = QueryInterface.Fusion.dbsf, limit = 5))

        assertEquals(Points.Query.VariantCase.FUSION, points.queries.single().query.variantCase)
        assertEquals(Points.Fusion.DBSF, points.queries.single().query.fusion)
    }

    @Test
    fun `queryBatch sends one QueryPoints per request and reads one result list per request`() = runTest {
        val results = transport.queryBatch(
            "docs",
            listOf(
                SearchRequest(query = QueryInterface.Vector(listOf(0.1f)), limit = 1),
                SearchRequest(query = QueryInterface.Vector(listOf(0.2f)), limit = 1),
            ),
        )

        assertEquals(2, points.queryBatches.single().queryPointsCount)
        assertEquals(2, results.size)
    }

    @Test
    fun `queryGroups carries the group field and reads the groups back`() = runTest {
        val groups = transport.queryGroups(
            "docs",
            SearchGroupsRequest(groupBy = "lang", groupSize = 2, limit = 3, query = QueryInterface.Vector(listOf(0.1f))),
        )

        assertEquals("lang", points.groupQueries.single().groupBy)
        assertEquals(JsonPrimitive("it"), groups.single().id)
        assertEquals(1, groups.single().hits.size)
    }

    // --- Scroll ------------------------------------------------------------------------------

    @Test
    fun `an ordered scroll sends the order and reads back the value it sorted on`() = runTest {
        val page = transport.scroll(
            "docs",
            ScrollRequest(limit = 10, orderBy = OrderBy(key = "n", direction = Direction.DESC)),
        )

        val request = points.scrolls.single()
        assertEquals("n", request.orderBy.key)
        assertEquals(Points.Direction.Desc, request.orderBy.direction)
        // Qdrant returns no cursor for an ordered scroll; the client pages on this value instead.
        assertEquals(JsonPrimitive(3L), page.points.single().orderValue)
    }

    @Test
    fun `a request that did not choose leaves the selectors off, so Qdrant applies its own defaults`() =
        runTest {
            // Scroll and retrieve default with_payload to true on the server. Sending an explicit
            // `enable = false` for "the caller said nothing" is a different request, and it silently
            // drops the payload from every call that did not ask for one.
            transport.scroll("docs", ScrollRequest(limit = 10))
            transport.retrieve("docs", listOf(PointId.num(1)), withPayload = null, withVector = null)

            assertFalse(points.scrolls.single().hasWithPayload())
            assertFalse(points.scrolls.single().hasWithVectors())
            assertFalse(points.gets.single().hasWithPayload())
            assertFalse(points.gets.single().hasWithVectors())
        }

    @Test
    fun `a scroll resuming from an id sends it as the offset`() = runTest {
        transport.scroll("docs", ScrollRequest(limit = 10, offset = PointId.num(7)))

        assertEquals(7L, points.scrolls.single().offset.num)
    }

    // --- Payload, vectors and batches --------------------------------------------------------

    @Test
    fun `setPayload sends the payload, the selector and the nested key`() = runTest {
        transport.setPayload(
            "docs",
            payloadOf("reviewed" to true),
            DeleteSelector.Ids(listOf(PointId.num(1))),
            key = "meta",
            wait = true,
        )

        val request = points.setPayloads.single()
        assertEquals(true, request.payloadMap.getValue("reviewed").boolValue)
        assertEquals("meta", request.key)
        assertEquals(1L, request.pointsSelector.points.getIds(0).num)
    }

    @Test
    fun `updateVectors sends one message per point, with its named vectors`() = runTest {
        transport.updateVectors(
            "docs",
            listOf(
                PointVectors(
                    PointId.num(1),
                    VectorData.Named(mapOf("text" to VectorData.Dense(listOf(0.9f)))),
                ),
            ),
            wait = true,
        )

        val request = points.vectorUpdates.single()
        assertEquals(listOf(0.9f), request.getPoints(0).vectors.vectors.vectorsMap.getValue("text").dense.dataList)
    }

    @Test
    fun `batchUpdate keeps the order it was given, because that is the only guarantee it has`() = runTest {
        transport.batchUpdate(
            "docs",
            listOf(
                PointsUpdateOperation.Upsert(listOf(PointStruct(PointId.num(1), VectorData.Dense(listOf(0.1f))))),
                PointsUpdateOperation.SetPayload(payloadOf("a" to 1), DeleteSelector.Ids(listOf(PointId.num(1)))),
                PointsUpdateOperation.ClearPayload(DeleteSelector.Ids(listOf(PointId.num(1)))),
            ),
            wait = true,
        )

        assertEquals(
            listOf(
                Points.PointsUpdateOperation.OperationCase.UPSERT,
                Points.PointsUpdateOperation.OperationCase.SET_PAYLOAD,
                Points.PointsUpdateOperation.OperationCase.CLEAR_PAYLOAD,
            ),
            points.batches.single().operationsList.map { it.operationCase },
        )
    }

    @Test
    fun `a payload index carries the field type Qdrant needs to build it`() = runTest {
        transport.createPayloadIndex("docs", "lang", PayloadSchemaType.KEYWORD, wait = true)

        assertEquals(Points.FieldType.FieldTypeKeyword, points.indexes.single().fieldType)
        assertEquals("lang", points.indexes.single().fieldName)
    }

    @Test
    fun `an index built with parameters sends them on the message beside the type`() = runTest {
        transport.createPayloadIndex(
            "docs",
            "body",
            PayloadIndexParams.Text(
                tokenizer = Tokenizer.MULTILINGUAL,
                minTokenLen = 2,
                maxTokenLen = 20,
                lowercase = true,
                phraseMatching = true,
                onDisk = true,
            ),
            wait = true,
        )

        val text = points.indexes.single().fieldIndexParams.textIndexParams
        assertEquals(Points.FieldType.FieldTypeText, points.indexes.single().fieldType)
        assertEquals(Collections.TokenizerType.Multilingual, text.tokenizer)
        assertEquals(2L, text.minTokenLen)
        assertEquals(20L, text.maxTokenLen)
        assertTrue(text.lowercase)
        assertTrue(text.phraseMatching)
        assertTrue(text.onDisk)
    }

    @Test
    fun `a parameter the caller left unset is not sent, so the server's default stands`() = runTest {
        transport.createPayloadIndex("docs", "tenant", PayloadIndexParams.Keyword(isTenant = true), wait = true)

        val keyword = points.indexes.single().fieldIndexParams.keywordIndexParams
        assertTrue(keyword.isTenant)
        assertFalse(keyword.hasOnDisk(), "on_disk was never asked for and must not be sent")
    }

    // --- Analytics and service ---------------------------------------------------------------

    @Test
    fun `facet reads each hit's value out of the variant it arrived in`() = runTest {
        val hits = transport.facet("docs", key = "lang", filter = null, limit = 5, exact = true)

        assertEquals(FacetValue.StringValue("it"), hits[0].value)
        assertEquals(2L, hits[0].count)
        assertEquals(FacetValue.IntValue(2024), hits[1].value)
        assertEquals(5L, points.facets.single().limit)
    }

    @Test
    fun `both matrix forms read their own result shape`() = runTest {
        val pairs = transport.searchMatrixPairs("docs", SearchMatrixRequest(sample = 10, limit = 2))
        val offsets = transport.searchMatrixOffsets("docs", SearchMatrixRequest(sample = 10, limit = 2))

        assertEquals(PointId.num(1), pairs.pairs.single().a)
        assertEquals(listOf(0L), offsets.offsetsRow)
        assertEquals(listOf(PointId.num(1)), offsets.ids)
    }

    @Test
    fun `aliases go out as one atomic batch of actions`() = runTest {
        transport.updateAliases(
            listOf(
                dev.kdrant.model.AliasOperation.Delete("docs"),
                dev.kdrant.model.AliasOperation.Create(collectionName = "docs-v2", aliasName = "docs"),
            ),
            timeout = 5,
        )

        val request = collections.aliasChanges.single()
        assertEquals(2, request.actionsCount)
        assertEquals("docs", request.getActions(0).deleteAlias.aliasName)
        assertEquals("docs-v2", request.getActions(1).createAlias.collectionName)
        assertEquals(5L, request.timeout)
    }

    @Test
    fun `a snapshot description keeps its name, size and creation instant`() = runTest {
        val snapshot = transport.createSnapshot("docs", wait = true)

        assertEquals("docs-2024.snapshot", snapshot.name)
        assertEquals(1024L, snapshot.size)
        assertEquals("2024-06-01T00:00:00Z", snapshot.creationTime)
    }

    // --- Fixtures ----------------------------------------------------------------------------

    private class RecordingPoints : PointsGrpcKt.PointsCoroutineImplBase() {
        val upserts = mutableListOf<Points.UpsertPoints>()
        val gets = mutableListOf<Points.GetPoints>()
        val counts = mutableListOf<Points.CountPoints>()
        val deletes = mutableListOf<Points.DeletePoints>()
        val queries = mutableListOf<Points.QueryPoints>()
        val queryBatches = mutableListOf<Points.QueryBatchPoints>()
        val groupQueries = mutableListOf<Points.QueryPointGroups>()
        val scrolls = mutableListOf<Points.ScrollPoints>()
        val setPayloads = mutableListOf<Points.SetPayloadPoints>()
        val vectorUpdates = mutableListOf<Points.UpdatePointVectors>()
        val batches = mutableListOf<Points.UpdateBatchPoints>()
        val indexes = mutableListOf<Points.CreateFieldIndexCollection>()
        val facets = mutableListOf<Points.FacetCounts>()

        override suspend fun upsert(request: Points.UpsertPoints) = accepted { upserts += request }

        override suspend fun delete(request: Points.DeletePoints) = accepted { deletes += request }

        override suspend fun setPayload(request: Points.SetPayloadPoints) = accepted { setPayloads += request }

        override suspend fun updateVectors(request: Points.UpdatePointVectors) = accepted { vectorUpdates += request }

        override suspend fun createFieldIndex(request: Points.CreateFieldIndexCollection) =
            accepted { indexes += request }

        override suspend fun get(request: Points.GetPoints): Points.GetResponse {
            gets += request
            return Points.GetResponse.newBuilder().addResult(retrievedPoint()).build()
        }

        override suspend fun count(request: Points.CountPoints): Points.CountResponse {
            counts += request
            return Points.CountResponse.newBuilder()
                .setResult(Points.CountResult.newBuilder().setCount(42))
                .build()
        }

        override suspend fun query(request: Points.QueryPoints): Points.QueryResponse {
            queries += request
            return Points.QueryResponse.newBuilder().addResult(scoredPoint()).build()
        }

        override suspend fun queryBatch(request: Points.QueryBatchPoints): Points.QueryBatchResponse {
            queryBatches += request
            val batch = Points.BatchResult.newBuilder().addResult(scoredPoint()).build()
            return Points.QueryBatchResponse.newBuilder().addResult(batch).addResult(batch).build()
        }

        override suspend fun queryGroups(request: Points.QueryPointGroups): Points.QueryGroupsResponse {
            groupQueries += request
            val group = Points.PointGroup.newBuilder()
                .setId(Points.GroupId.newBuilder().setStringValue("it"))
                .addHits(scoredPoint())
                .build()
            return Points.QueryGroupsResponse.newBuilder()
                .setResult(Points.GroupsResult.newBuilder().addGroups(group))
                .build()
        }

        override suspend fun scroll(request: Points.ScrollPoints): Points.ScrollResponse {
            scrolls += request
            return Points.ScrollResponse.newBuilder()
                .addResult(
                    retrievedPoint().toBuilder()
                        .setOrderValue(Points.OrderValue.newBuilder().setInt(3))
                        .build(),
                )
                .build()
        }

        override suspend fun updateBatch(request: Points.UpdateBatchPoints): Points.UpdateBatchResponse {
            batches += request
            return Points.UpdateBatchResponse.getDefaultInstance()
        }

        override suspend fun facet(request: Points.FacetCounts): Points.FacetResponse {
            facets += request
            return Points.FacetResponse.newBuilder()
                .addHits(facetHit(Points.FacetValue.newBuilder().setStringValue("it").build(), 2))
                .addHits(facetHit(Points.FacetValue.newBuilder().setIntegerValue(2024).build(), 1))
                .build()
        }

        override suspend fun searchMatrixPairs(request: Points.SearchMatrixPoints): Points.SearchMatrixPairsResponse =
            Points.SearchMatrixPairsResponse.newBuilder()
                .setResult(
                    Points.SearchMatrixPairs.newBuilder().addPairs(
                        Points.SearchMatrixPair.newBuilder()
                            .setA(id(1))
                            .setB(id(2))
                            .setScore(0.5f),
                    ),
                )
                .build()

        override suspend fun searchMatrixOffsets(
            request: Points.SearchMatrixPoints,
        ): Points.SearchMatrixOffsetsResponse =
            Points.SearchMatrixOffsetsResponse.newBuilder()
                .setResult(
                    Points.SearchMatrixOffsets.newBuilder()
                        .addOffsetsRow(0)
                        .addOffsetsCol(1)
                        .addScores(0.5f)
                        .addIds(id(1)),
                )
                .build()

        private inline fun accepted(record: () -> Unit): Points.PointsOperationResponse {
            record()
            return Points.PointsOperationResponse.newBuilder()
                .setResult(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed))
                .build()
        }

        private fun facetHit(value: Points.FacetValue, count: Long): Points.FacetHit =
            Points.FacetHit.newBuilder().setValue(value).setCount(count).build()

        private fun retrievedPoint(): Points.RetrievedPoint = Points.RetrievedPoint.newBuilder()
            .setId(id(1))
            .putPayload("lang", qdrant.JsonWithInt.Value.newBuilder().setStringValue("it").build())
            .build()

        private fun scoredPoint(): Points.ScoredPoint = Points.ScoredPoint.newBuilder()
            .setId(id(9))
            .setScore(0.75f)
            .build()

        private fun id(value: Long) = qdrant.Common.PointId.newBuilder().setNum(value).build()
    }

    private class RecordingCollections : CollectionsGrpcKt.CollectionsCoroutineImplBase() {
        val created = mutableListOf<Collections.CreateCollection>()
        val aliasChanges = mutableListOf<Collections.ChangeAliases>()

        override suspend fun create(request: Collections.CreateCollection): Collections.CollectionOperationResponse {
            created += request
            return Collections.CollectionOperationResponse.newBuilder().setResult(true).build()
        }

        override suspend fun updateAliases(
            request: Collections.ChangeAliases,
        ): Collections.CollectionOperationResponse {
            aliasChanges += request
            return Collections.CollectionOperationResponse.newBuilder().setResult(true).build()
        }

        override suspend fun collectionExists(
            request: Collections.CollectionExistsRequest,
        ): Collections.CollectionExistsResponse =
            Collections.CollectionExistsResponse.newBuilder()
                .setResult(Collections.CollectionExists.newBuilder().setExists(true))
                .build()

        override suspend fun get(
            request: Collections.GetCollectionInfoRequest,
        ): Collections.GetCollectionInfoResponse {
            val params = Collections.CollectionParams.newBuilder()
                .setVectorsConfig(
                    Collections.VectorsConfig.newBuilder().setParams(
                        Collections.VectorParams.newBuilder()
                            .setSize(4)
                            .setDistance(Collections.Distance.Cosine),
                    ),
                )
                .setShardNumber(1)
                .build()
            val info = Collections.CollectionInfo.newBuilder()
                .setStatus(Collections.CollectionStatus.Green)
                .setPointsCount(7)
                .setConfig(Collections.CollectionConfig.newBuilder().setParams(params))
                .putPayloadSchema(
                    "lang",
                    Collections.PayloadSchemaInfo.newBuilder()
                        .setDataType(Collections.PayloadSchemaType.Keyword)
                        .build(),
                )
                .build()
            return Collections.GetCollectionInfoResponse.newBuilder().setResult(info).build()
        }
    }

    private class RecordingSnapshots : SnapshotsGrpcKt.SnapshotsCoroutineImplBase() {
        override suspend fun create(
            request: SnapshotsService.CreateSnapshotRequest,
        ): SnapshotsService.CreateSnapshotResponse =
            SnapshotsService.CreateSnapshotResponse.newBuilder()
                .setSnapshotDescription(
                    SnapshotsService.SnapshotDescription.newBuilder()
                        .setName("docs-2024.snapshot")
                        .setSize(1024)
                        .setCreationTime(
                            com.google.protobuf.Timestamp.newBuilder().setSeconds(1_717_200_000).build(),
                        ),
                )
                .build()
    }

    private companion object {
        const val UUID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
