package dev.kdrant.testkit

import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.payloadOf
import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Direction
import dev.kdrant.model.Distance
import dev.kdrant.model.FacetValue
import dev.kdrant.model.OptimizersConfig
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.VectorData
import dev.kdrant.model.VectorsConfig
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a Kdrant engine has to do, stated once and run against every engine.
 *
 * The REST tests that came before this asserted HTTP bodies through Ktor's `MockEngine`, which is the
 * right way to pin a wire format and the wrong way to compare two wire formats: a gRPC engine cannot
 * satisfy an assertion about a JSON body by construction. So this suite never mentions a protocol. It
 * receives a [QdrantClient] from the subclass, talks to a real Qdrant in Docker, and asserts on what
 * comes back. Anything an engine has to do differently to pass is a difference the caller would have
 * seen too.
 *
 * Scope is the operations both engines can perform. Qdrant's gRPC API has no telemetry, no metrics, no
 * issues endpoint, no snapshot transfer and no shard-scope snapshots, so those stay in the REST module's
 * own tests rather than being weakened here into something both can pass. The gRPC module asserts
 * separately that each of them fails with a message naming REST.
 *
 * Skipped, not failed, when Docker is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class QdrantClientContract {

    private lateinit var container: QdrantContainer
    private lateinit var client: QdrantClient
    private val collections = AtomicInteger()

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
    }

    @AfterAll
    public fun stopQdrant() {
        if (::client.isInitialized) client.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    // --- Collections -------------------------------------------------------------------------

    @Test
    public fun `a collection can be created, found, described and deleted`(): Unit = runBlocking {
        val name = nextName()
        assertFalse(client.collectionExists(name), "the collection existed before it was created")

        client.createCollection(name) {
            vector { size = 4; distance = Distance.COSINE }
            onDiskPayload = true
        }

        assertTrue(client.collectionExists(name))
        assertEquals(CollectionStatus.GREEN, client.getCollection(name).status)
        assertTrue(client.listCollections().any { it.name == name }, "the collection is missing from the list")

        client.deleteCollection(name)
        assertFalse(client.collectionExists(name))
    }

    @Test
    public fun `a named-vectors collection reports the names, sizes and distances it was created with`():
        Unit = runBlocking {
        withCollection(
            create = {
                namedVector("text") { size = 8; distance = Distance.COSINE }
                namedVector("image") { size = 16; distance = Distance.DOT }
            },
        ) { name ->
            val vectors = (client.getCollection(name).config?.params?.vectors as VectorsConfig.Named).vectors

            assertEquals(setOf("text", "image"), vectors.keys)
            assertEquals(8L, vectors.getValue("text").size)
            assertEquals(Distance.COSINE, vectors.getValue("text").distance)
            assertEquals(16L, vectors.getValue("image").size)
            assertEquals(Distance.DOT, vectors.getValue("image").distance)
        }
    }

    @Test
    public fun `ensureCollection creates once and then accepts the collection it finds`(): Unit = runBlocking {
        val name = nextName()
        try {
            assertTrue(client.ensureCollection(name) { vector { size = 4; distance = Distance.COSINE } })
            assertFalse(client.ensureCollection(name) { vector { size = 4; distance = Distance.COSINE } })
        } finally {
            drop(name)
        }
    }

    @Test
    public fun `updateCollection is accepted and leaves the collection serving`(): Unit = runBlocking {
        withCollection { name ->
            client.updateCollection(name) { optimizers = OptimizersConfig(indexingThreshold = 30_000) }

            assertEquals(CollectionStatus.GREEN, client.getCollection(name).status)
        }
    }

    // --- Points ------------------------------------------------------------------------------

    @Test
    public fun `points upserted from a list come back by id with their payload and vector`(): Unit = runBlocking {
        withCollection { name ->
            client.upsert(name, wait = true) {
                point(1) {
                    vector(0.1f, 0.2f, 0.3f, 0.4f)
                    payload("lang" to "it", "year" to 2024, "public" to true)
                }
                point(UUID) { vector(0.4f, 0.3f, 0.2f, 0.1f) }
            }

            val records = client.retrieve(name, listOf(PointId.num(1)), WithPayload.All, withVector = true)

            val record = records.single()
            assertEquals(PointId.num(1), record.id)
            assertEquals("it", record.payload?.get("lang")?.toString()?.trim('"'))
            assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), denseOf(record.vector))
            assertEquals(2L, client.count(name))
        }
    }

    @Test
    public fun `a uuid id and a numeric id are two different points`(): Unit = runBlocking {
        withCollection { name ->
            client.upsert(name, wait = true) {
                point(1) { vector(0.1f, 0.2f, 0.3f, 0.4f) }
                point(UUID) { vector(0.1f, 0.2f, 0.3f, 0.4f) }
            }

            val ids = client.retrieve(name, listOf(PointId.num(1), PointId.uuid(UUID))).map { it.id }.toSet()

            assertEquals(setOf(PointId.num(1), PointId.uuid(UUID)), ids)
        }
    }

    @Test
    public fun `points upserted from a flow are all stored`(): Unit = runBlocking {
        withCollection { name ->
            val points = (1L..25L).map { id ->
                PointStruct(
                    id = PointId.num(id),
                    vector = VectorData.Dense(listOf(0.1f, 0.2f, 0.3f, id / 100f)),
                    payload = payloadOf("n" to id),
                )
            }

            client.upsert(name, points.asFlow(), wait = true)

            assertEquals(25L, client.count(name))
        }
    }

    @Test
    public fun `count honours a filter, and an unmatched filter counts zero`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            assertEquals(3L, client.count(name))
            assertEquals(2L, client.count(name) { must { "lang" eq "it" } })
            assertEquals(0L, client.count(name) { must { "lang" eq "de" } })
        }
    }

    @Test
    public fun `points can be deleted by id and by filter`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            client.delete(name, listOf(PointId.num(1)), wait = true)
            assertEquals(2L, client.count(name))

            client.delete(name, wait = true) { must { "lang" eq "it" } }
            assertEquals(1L, client.count(name))
        }
    }

    // --- Search ------------------------------------------------------------------------------

    @Test
    public fun `search returns the nearest point first, with the payload that was asked for`(): Unit =
        runBlocking {
            withCollection { name ->
                seed(name)

                val hits = client.search(name) {
                    query(0.9f, 0.1f, 0.0f, 0.0f)
                    limit = 3
                    withPayload = WithPayload.All
                }

                assertEquals(3, hits.size)
                assertEquals(PointId.num(1), hits.first().id, "the point aligned with the query should rank first")
                assertNotNull(hits.first().payload)
                assertTrue(hits[0].score >= hits[1].score, "hits should come back ordered by score")
            }
        }

    @Test
    public fun `a search filter narrows the candidates rather than the returned page`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val hits = client.search(name) {
                query(0.9f, 0.1f, 0.0f, 0.0f)
                limit = 10
                withPayload = WithPayload.All
                filter { must { "lang" eq "en" } }
            }

            assertEquals(1, hits.size)
            assertEquals(PointId.num(3), hits.single().id)
        }
    }

    @Test
    public fun `withPayload selects fields, and withVector decides whether vectors come back`(): Unit =
        runBlocking {
            withCollection { name ->
                seed(name)

                val included = client.search(name) {
                    query(0.9f, 0.1f, 0.0f, 0.0f)
                    limit = 1
                    withPayload = WithPayload.include("lang")
                    withVector = true
                }.single()
                val bare = client.search(name) {
                    query(0.9f, 0.1f, 0.0f, 0.0f)
                    limit = 1
                }.single()

                assertEquals(setOf("lang"), included.payload?.keys)
                assertNotNull(included.vector)
                assertNull(bare.payload)
                assertNull(bare.vector)
            }
        }

    @Test
    public fun `searchBatch answers each query in the order they were given`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val results = client.searchBatch(name) {
                search { query(0.9f, 0.1f, 0.0f, 0.0f); limit = 1 }
                search { query(0.0f, 0.0f, 0.1f, 0.9f); limit = 1 }
            }

            assertEquals(2, results.size)
            assertEquals(PointId.num(1), results[0].single().id)
            assertEquals(PointId.num(3), results[1].single().id)
        }
    }

    @Test
    public fun `searchGroups groups the hits by a payload field`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            client.createPayloadIndex(name, "lang", PayloadSchemaType.KEYWORD, wait = true)
            val groups = client.searchGroups(name, groupBy = "lang", groupSize = 2, limit = 5) {
                query(0.9f, 0.1f, 0.0f, 0.0f)
            }

            assertEquals(2, groups.size, "two languages were seeded, so there should be two groups")
            assertTrue(groups.all { it.hits.isNotEmpty() })
        }
    }

    // --- Scroll ------------------------------------------------------------------------------

    @Test
    public fun `scroll emits every point exactly once across pages`(): Unit = runBlocking {
        withCollection { name ->
            client.upsert(name, wait = true) {
                for (id in 1L..30L) point(id) { vector(0.1f, 0.2f, 0.3f, id / 100f) }
            }

            val ids = client.scroll(name, pageSize = 7).map { it.id }.toList()

            assertEquals(30, ids.size)
            assertEquals(30, ids.toSet().size, "a point was emitted twice")
        }
    }

    @Test
    public fun `a filtered scroll returns only the matching points`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val ids = client.scroll(name, pageSize = 2) { filter { must { "lang" eq "it" } } }.map { it.id }.toList()

            assertEquals(listOf(PointId.num(1), PointId.num(2)), ids.sortedBy { (it as PointId.Num).value })
        }
    }

    @Test
    public fun `an ordered scroll comes back in the order it asked for`(): Unit = runBlocking {
        withCollection { name ->
            client.createPayloadIndex(name, "n", PayloadSchemaType.INTEGER, wait = true)
            client.upsert(name, wait = true) {
                for (id in 1L..10L) point(id) { vector(0.1f, 0.2f, 0.3f, 0.4f); payload("n" to id) }
            }

            val order = client.scroll(name, pageSize = 3) { orderBy("n", Direction.DESC) }
                .map { it.payload?.get("n").toString().toLong() }
                .toList()

            assertEquals((10L downTo 1L).toList(), order)
        }
    }

    // --- Payload and vectors -----------------------------------------------------------------

    @Test
    public fun `setPayload merges, overwritePayload replaces, the other two remove`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val one = DeleteSelector.Ids(listOf(PointId.num(1)))

            client.setPayload(name, payloadOf("reviewed" to true), one, wait = true)
            assertEquals(setOf("lang", "year", "reviewed"), payloadKeys(name, 1))

            client.overwritePayload(name, payloadOf("lang" to "it"), one, wait = true)
            assertEquals(setOf("lang"), payloadKeys(name, 1))

            client.setPayload(name, payloadOf("reviewed" to true), one, wait = true)
            client.deletePayload(name, listOf("reviewed"), one, wait = true)
            assertEquals(setOf("lang"), payloadKeys(name, 1))

            client.clearPayload(name, one, wait = true)
            assertTrue(payloadKeys(name, 1).isEmpty())
        }
    }

    @Test
    public fun `named vectors can be updated and deleted one at a time`(): Unit = runBlocking {
        withCollection(
            create = {
                namedVector("text") { size = 2; distance = Distance.COSINE }
                namedVector("image") { size = 2; distance = Distance.COSINE }
            },
        ) { name ->
            client.upsert(name, wait = true) {
                point(1) { vector("text" to listOf(0.1f, 0.2f), "image" to listOf(0.3f, 0.4f)) }
            }

            client.updateVectors(
                name,
                listOf(
                    PointVectors(
                        PointId.num(1),
                        VectorData.Named(
                            mapOf(
                                "text" to VectorData.Dense(listOf(0.9f, 0.1f)),
                            ),
                        ),
                    ),
                ),
                wait = true,
            )
            val updated = client.retrieve(name, listOf(PointId.num(1)), withVector = true).single()
            assertEquals(
                listOf(0.9f, 0.1f),
                ((updated.vector as VectorData.Named).vectors.getValue("text") as VectorData.Dense).values,
            )

            client.deleteVectors(name, listOf("image"), DeleteSelector.Ids(listOf(PointId.num(1))), wait = true)
            val trimmed = client.retrieve(name, listOf(PointId.num(1)), withVector = true).single()
            assertEquals(setOf("text"), (trimmed.vector as VectorData.Named).vectors.keys)
        }
    }

    @Test
    public fun `a payload index can be created and dropped`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            client.createPayloadIndex(name, "lang", PayloadSchemaType.KEYWORD, wait = true)
            assertTrue(client.getCollection(name).payloadSchema.containsKey("lang"))

            client.deletePayloadIndex(name, "lang", wait = true)
            assertFalse(client.getCollection(name).payloadSchema.containsKey("lang"))
        }
    }

    @Test
    public fun `batchUpdate applies its operations in order`(): Unit = runBlocking {
        withCollection { name ->
            client.batchUpdate(name, wait = true) {
                upsert {
                    point(1) { vector(0.1f, 0.2f, 0.3f, 0.4f); payload("stale" to true) }
                    point(2) { vector(0.2f, 0.1f, 0.4f, 0.3f); payload("stale" to false) }
                }
                setPayload(payloadOf("reviewed" to true), byId(2L))
                delete(byFilter { must { "stale" eq true } })
            }

            // The delete sees the upsert that preceded it, and the point it removed is the one the
            // filter matched at that moment rather than before the batch started.
            assertEquals(1L, client.count(name))
            assertEquals(setOf("stale", "reviewed"), payloadKeys(name, 2))
        }
    }

    // --- Filters against a real server -------------------------------------------------------

    @Test
    public fun `the filter clauses combine the way Qdrant combines them`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            assertEquals(2L, client.count(name) { must { "lang" eq "it"; "year" gte 2023 } })
            assertEquals(3L, client.count(name) { should { "lang" eq "it"; "lang" eq "en" } })
            assertEquals(1L, client.count(name) { mustNot { "lang" eq "it" } })
            assertEquals(3L, client.count(name) { minShould(1) { "lang" eq "it"; "year" gte 2020 } })
        }
    }

    @Test
    public fun `every matcher the DSL offers reaches the server and is understood`(): Unit = runBlocking {
        withCollection { name ->
            client.upsert(name, wait = true) {
                point(1) {
                    vector(0.1f, 0.2f, 0.3f, 0.4f)
                    payload(
                        "lang" to "it",
                        "year" to 2024,
                        "score" to 4.5,
                        "title" to "a vector database for Kotlin",
                        "tags" to listOf("kotlin", "qdrant"),
                        "at" to "2024-06-01T00:00:00Z",
                    )
                }
            }
            // The text matchers need a full-text index; the others read the payload directly.
            client.createPayloadIndex(name, "title", PayloadSchemaType.TEXT, wait = true)
            client.createPayloadIndex(name, "at", PayloadSchemaType.DATETIME, wait = true)

            assertEquals(1L, client.count(name) { must { "lang" eq "it" } })
            assertEquals(1L, client.count(name) { must { matchAny("lang", "it", "en") } })
            assertEquals(1L, client.count(name) { must { matchExcept("lang", "de") } })
            assertEquals(1L, client.count(name) { must { matchText("title", "vector Kotlin") } })
            assertEquals(1L, client.count(name) { must { matchTextAny("title", "vector rust") } })
            assertEquals(1L, client.count(name) { must { matchPhrase("title", "vector database") } })
            assertEquals(1L, client.count(name) { must { "score" between 4.0..5.0 } })
            assertEquals(1L, client.count(name) { must { valuesCount("tags", gte = 2) } })
            assertEquals(1L, client.count(name) { must { hasId(PointId.num(1)) } })
            assertEquals(1L, client.count(name) { must { hasVector("") } })
            assertEquals(1L, client.count(name) { must { isEmpty("missing") } })
            assertEquals(
                1L,
                client.count(name) {
                    must { datetimeRange("at", gte = "2024-01-01T00:00:00Z", lt = "2025-01-01T00:00:00Z") }
                },
            )
            assertEquals(1L, client.count(name) { must { filter { must { "lang" eq "it" } } } })
        }
    }

    // --- Aliases -----------------------------------------------------------------------------

    @Test
    public fun `an alias can be created, listed, renamed and dropped`(): Unit = runBlocking {
        withCollection { name ->
            val alias = "$name-alias"
            val renamed = "$name-current"

            client.updateAliases { createAlias(collection = name, alias = alias) }
            assertTrue(client.listAliases().any { it.aliasName == alias })
            assertEquals(listOf(alias), client.listCollectionAliases(name).map { it.aliasName })

            client.updateAliases { renameAlias(from = alias, to = renamed) }
            assertEquals(listOf(renamed), client.listCollectionAliases(name).map { it.aliasName })

            client.updateAliases { deleteAlias(renamed) }
            assertTrue(client.listCollectionAliases(name).isEmpty())
        }
    }

    // --- Analytics ---------------------------------------------------------------------------

    @Test
    public fun `facet counts the distinct values of a payload field`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            client.createPayloadIndex(name, "lang", PayloadSchemaType.KEYWORD, wait = true)
            val hits = client.facet(name, key = "lang", exact = true).associate { it.value to it.count }

            assertEquals(2L, hits[FacetValue.StringValue("it")])
            assertEquals(1L, hits[FacetValue.StringValue("en")])
        }
    }

    @Test
    public fun `the distance matrix comes back in both of its forms`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val pairs = client.searchMatrixPairs(name) { sample = 10; limit = 2 }
            val offsets = client.searchMatrixOffsets(name) { sample = 10; limit = 2 }

            assertTrue(pairs.pairs.isNotEmpty(), "the pairs form returned no edges")
            assertEquals(offsets.offsetsRow.size, offsets.offsetsCol.size)
            assertEquals(offsets.offsetsRow.size, offsets.scores.size)
            assertTrue(offsets.ids.isNotEmpty())
        }
    }

    // --- Service -----------------------------------------------------------------------------

    @Test
    public fun `a running node reports itself healthy, ready and alive`(): Unit = runBlocking {
        assertTrue(client.healthz(), "healthz")
        assertTrue(client.readyz(), "readyz")
        assertTrue(client.livez(), "livez")
    }

    @Test
    public fun `cluster info describes the collection's shards`(): Unit = runBlocking {
        withCollection { name ->
            val info = client.collectionClusterInfo(name)

            assertTrue(info.localShards.isNotEmpty() || info.remoteShards.isNotEmpty(), "no shard reported")
        }
    }

    // --- Snapshots ---------------------------------------------------------------------------

    @Test
    public fun `a collection snapshot can be created, listed and deleted`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val snapshot = client.createSnapshot(name)
            assertTrue(client.listSnapshots(name).any { it.name == snapshot.name })

            client.deleteSnapshot(name, snapshot.name)
            assertTrue(client.listSnapshots(name).none { it.name == snapshot.name })
        }
    }

    @Test
    public fun `a whole-storage snapshot can be created, listed and deleted`(): Unit = runBlocking {
        withCollection { name ->
            seed(name)

            val snapshot = client.createStorageSnapshot()
            assertTrue(client.listStorageSnapshots().any { it.name == snapshot.name })

            client.deleteStorageSnapshot(snapshot.name)
            assertTrue(client.listStorageSnapshots().none { it.name == snapshot.name })
        }
    }

    // --- Failures ----------------------------------------------------------------------------

    @Test
    public fun `an operation on a collection that does not exist reports it as such`(): Unit = runBlocking {
        val missing = "no-such-collection-${collections.incrementAndGet()}"

        assertThrowsCollectionNotFound { client.getCollection(missing) }
        assertThrowsCollectionNotFound { client.count(missing) }
        assertThrowsCollectionNotFound { client.search(missing) { query(0.1f, 0.2f, 0.3f, 0.4f) } }
    }

    // --- Fixtures ----------------------------------------------------------------------------

    private suspend fun assertThrowsCollectionNotFound(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(
            error is KdrantException.CollectionNotFound,
            "expected CollectionNotFound, got ${error?.let { it::class.simpleName + ": " + it.message }}",
        )
    }

    /** Three points, two Italian and one English, spread far enough apart that ranking is unambiguous. */
    private suspend fun seed(name: String) {
        client.upsert(name, wait = true) {
            point(1) { vector(1.0f, 0.0f, 0.0f, 0.0f); payload("lang" to "it", "year" to 2024) }
            point(2) { vector(0.0f, 1.0f, 0.0f, 0.0f); payload("lang" to "it", "year" to 2023) }
            point(3) { vector(0.0f, 0.0f, 0.0f, 1.0f); payload("lang" to "en", "year" to 2022) }
        }
    }

    private suspend fun payloadKeys(name: String, id: Long): Set<String> =
        client.retrieve(name, listOf(PointId.num(id)), WithPayload.All).single().payload?.keys.orEmpty()

    private fun denseOf(vector: VectorData?): List<Float>? = (vector as? VectorData.Dense)?.values

    private fun nextName(): String = "contract-${collections.incrementAndGet()}"

    private suspend fun drop(name: String) {
        runCatching { client.deleteCollection(name) }
    }

    /**
     * Runs [block] against a collection of its own. Every test gets a fresh name, so a failure leaves
     * nothing behind for the next one to trip over and the tests do not have to run in order.
     */
    private suspend fun withCollection(
        create: CreateCollectionBuilder.() -> Unit = {
            vector { size = 4; distance = Distance.COSINE }
        },
        block: suspend (String) -> Unit,
    ) {
        val name = nextName()
        client.createCollection(name, create)
        try {
            block(name)
        } finally {
            drop(name)
        }
    }

    private companion object {
        /** Overridable so CI can hold both engines to a matrix of Qdrant versions. */
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
        const val UUID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
