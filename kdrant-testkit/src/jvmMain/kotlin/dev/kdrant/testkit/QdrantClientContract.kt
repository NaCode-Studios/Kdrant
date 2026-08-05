package dev.kdrant.testkit

import dev.kdrant.QdrantClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer

/**
 * The client contract as JUnit, against a real Qdrant in Docker.
 *
 * The behaviours themselves are in [QdrantClientContractSuite], which knows no test framework and
 * compiles for every target `kdrant-core` does. This class is the JVM half: it starts the container,
 * builds the client the subclass asks for, and declares one test per behaviour so a failure names
 * itself in the report. A native test binary runs the same suite by walking
 * [QdrantClientContractSuite.cases].
 *
 * Skipped, not failed, when Docker is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class QdrantClientContract {

    private lateinit var container: QdrantContainer
    private lateinit var client: QdrantClient
    private lateinit var suite: QdrantClientContractSuite

    /**
     * Builds the client under test. The container exposes both of Qdrant's ports, so an engine picks
     * the one it speaks: [QdrantContainer.getGrpcPort], or `getMappedPort(6333)` for REST.
     */
    protected abstract fun connect(container: QdrantContainer): QdrantClient

    @BeforeAll
    public fun startQdrant() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the Qdrant client contract",
        )
        container = QdrantContainer(IMAGE).also { it.start() }
        client = connect(container)
        suite = QdrantClientContractSuite(client)
    }

    @AfterAll
    public fun stopQdrant() {
        if (::client.isInitialized) client.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    // --- Collections -------------------------------------------------------------------------

    @Test
    public fun `a collection can be created, found, described and deleted`(): Unit =
        runBlocking { suite.collectionLifecycle() }

    @Test
    public fun `a named-vectors collection reports the names, sizes and distances it was created with`(): Unit =
        runBlocking { suite.namedVectorsConfig() }

    @Test
    public fun `ensureCollection creates once and then accepts the collection it finds`(): Unit =
        runBlocking { suite.ensureCollectionIsIdempotent() }

    @Test
    public fun `updateCollection is accepted and leaves the collection serving`(): Unit =
        runBlocking { suite.updateCollectionKeepsItServing() }

    // --- Points ------------------------------------------------------------------------------

    @Test
    public fun `points upserted from a list come back by id with their payload and vector`(): Unit =
        runBlocking { suite.upsertFromList() }

    @Test
    public fun `a cosine collection stores the vector normalized, not as it was written`(): Unit =
        runBlocking { suite.cosineNormalizesOnWrite() }

    @Test
    public fun `a uuid id and a numeric id are two different points`(): Unit =
        runBlocking { suite.uuidAndNumericIdsAreDistinct() }

    @Test
    public fun `points upserted from a flow are all stored`(): Unit =
        runBlocking { suite.upsertFromFlow() }

    @Test
    public fun `count honours a filter, and an unmatched filter counts zero`(): Unit =
        runBlocking { suite.countHonoursAFilter() }

    @Test
    public fun `points can be deleted by id and by filter`(): Unit =
        runBlocking { suite.deleteByIdAndByFilter() }

    // --- Search ------------------------------------------------------------------------------

    @Test
    public fun `search returns the nearest point first, with the payload that was asked for`(): Unit =
        runBlocking { suite.searchRanksNearestFirst() }

    @Test
    public fun `a search filter narrows the candidates rather than the returned page`(): Unit =
        runBlocking { suite.searchFilterNarrowsCandidates() }

    @Test
    public fun `withPayload selects fields, and withVector decides whether vectors come back`(): Unit =
        runBlocking { suite.withPayloadAndWithVector() }

    @Test
    public fun `searchBatch answers each query in the order they were given`(): Unit =
        runBlocking { suite.searchBatchKeepsOrder() }

    @Test
    public fun `searchGroups groups the hits by a payload field`(): Unit =
        runBlocking { suite.searchGroupsByPayloadField() }

    // --- Sparse, multi-vector and hybrid ------------------------------------------------------

    @Test
    public fun `a sparse vector round-trips and answers a sparse query`(): Unit =
        runBlocking { suite.sparseVectors() }

    @Test
    public fun `an IDF sparse collection scores by rarity, not by the value that was sent`(): Unit =
        runBlocking { suite.sparseIdfIsAppliedByTheServer() }

    @Test
    public fun `hybrid search fuses a dense and a sparse ranking`(): Unit =
        runBlocking { suite.hybridSearchFusesBothRankings() }

    @Test
    public fun `a multi-vector collection stores and scores late interaction`(): Unit =
        runBlocking { suite.multiVectors() }

    @Test
    public fun `a query naming a vector the collection does not have is refused`(): Unit =
        runBlocking { suite.unknownVectorNameIsRefused() }

    // --- Scroll ------------------------------------------------------------------------------

    @Test
    public fun `scroll emits every point exactly once across pages`(): Unit =
        runBlocking { suite.scrollEmitsEachPointOnce() }

    @Test
    public fun `a filtered scroll returns only the matching points`(): Unit =
        runBlocking { suite.filteredScroll() }

    @Test
    public fun `an ordered scroll comes back in the order it asked for`(): Unit =
        runBlocking { suite.orderedScroll() }

    // --- Payload and vectors -----------------------------------------------------------------

    @Test
    public fun `setPayload merges, overwritePayload replaces, the other two remove`(): Unit =
        runBlocking { suite.payloadMutations() }

    @Test
    public fun `named vectors can be updated and deleted one at a time`(): Unit =
        runBlocking { suite.namedVectorMutations() }

    @Test
    public fun `a payload index can be created and dropped`(): Unit =
        runBlocking { suite.payloadIndexLifecycle() }

    @Test
    public fun `a payload index takes the parameters its type accepts`(): Unit =
        runBlocking { suite.payloadIndexParameters() }

    @Test
    public fun `batchUpdate applies its operations in order`(): Unit =
        runBlocking { suite.batchUpdateIsOrdered() }

    @Test
    public fun `an ingest killed partway resumes from its token without re-sending`(): Unit =
        runBlocking { suite.ingestResumesFromItsToken() }

    // --- Filters against a real server -------------------------------------------------------

    @Test
    public fun `the filter clauses combine the way Qdrant combines them`(): Unit =
        runBlocking { suite.filterClausesCombine() }

    @Test
    public fun `every matcher the DSL offers reaches the server and is understood`(): Unit =
        runBlocking { suite.everyMatcherReachesTheServer() }

    // --- Aliases -----------------------------------------------------------------------------

    @Test
    public fun `an alias can be created, listed, renamed and dropped`(): Unit =
        runBlocking { suite.aliasLifecycle() }

    // --- Analytics ---------------------------------------------------------------------------

    @Test
    public fun `facet counts the distinct values of a payload field`(): Unit =
        runBlocking { suite.facetCountsDistinctValues() }

    @Test
    public fun `the distance matrix comes back in both of its forms`(): Unit =
        runBlocking { suite.distanceMatrixBothForms() }

    // --- Service -----------------------------------------------------------------------------

    @Test
    public fun `a running node reports itself healthy, ready and alive`(): Unit =
        runBlocking { suite.healthProbes() }

    @Test
    public fun `cluster info describes the collection's shards`(): Unit =
        runBlocking { suite.clusterInfoDescribesShards() }

    // --- Snapshots ---------------------------------------------------------------------------

    @Test
    public fun `a collection snapshot can be created, listed and deleted`(): Unit =
        runBlocking { suite.collectionSnapshotLifecycle() }

    @Test
    public fun `a whole-storage snapshot can be created, listed and deleted`(): Unit =
        runBlocking { suite.storageSnapshotLifecycle() }

    // --- Server-side inference -----------------------------------------------------------------

    /**
     * Skipped unless the Qdrant under test has an inference provider. A container does not, and faking
     * one would assert that Kdrant can talk to a fake.
     */
    @Test
    public fun `a document upserted and queried is embedded by the server`() {
        val model = System.getenv("KDRANT_INFERENCE_MODEL")
        val size = System.getenv("KDRANT_INFERENCE_SIZE")?.toLongOrNull()
        assumeTrue(
            model != null && size != null,
            "no inference provider configured; set KDRANT_INFERENCE_MODEL and KDRANT_INFERENCE_SIZE to run this",
        )
        runBlocking { suite.inferenceRoundTrip(model!!, size!!) }
    }

    // --- Failures ----------------------------------------------------------------------------

    @Test
    public fun `an operation on a collection that does not exist reports it as such`(): Unit =
        runBlocking { suite.missingCollectionIsReported() }

    private companion object {
        /** Overridable so CI can hold every engine to a matrix of Qdrant versions. */
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
    }
}
