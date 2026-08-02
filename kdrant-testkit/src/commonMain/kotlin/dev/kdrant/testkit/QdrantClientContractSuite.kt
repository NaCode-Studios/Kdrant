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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a Kdrant engine has to do, stated once and run against every engine on every platform.
 *
 * The REST tests that came before this asserted HTTP bodies through Ktor's `MockEngine`, which is the
 * right way to pin a wire format and the wrong way to compare two wire formats: a gRPC engine cannot
 * satisfy an assertion about a JSON body by construction. So this suite never mentions a protocol. It
 * receives a [QdrantClient] that is already connected to a real Qdrant, and asserts on what comes
 * back. Anything an engine has to do differently to pass is a difference the caller would have seen
 * too.
 *
 * Scope is the operations every engine can perform. Qdrant's gRPC API has no telemetry, no metrics, no
 * issues endpoint, no snapshot transfer and no shard-scope snapshots, so those stay in the REST module's
 * own tests rather than being weakened here into something both can pass. The gRPC module asserts
 * separately that each of them fails with a message naming REST.
 *
 * The suite holds no test framework. [QdrantClientContract] wraps it as JUnit on the JVM, where Docker
 * and Testcontainers are; a native test binary runs [cases] against a Qdrant that is already up. Both
 * run the same assertions, which is the whole point — an engine that has only ever been exercised from
 * a JVM has been proven to link, not to work.
 *
 * @property client the connected client under test. Closing it is the caller's business.
 * @property namePrefix prefix for the collections this suite creates, so two suites can share a node.
 */
public class QdrantClientContractSuite(
    private val client: QdrantClient,
    private val namePrefix: String = "contract",
) {

    // Not atomic: the cases run one after another, on the JVM because JUnit does not parallelise a
    // class by default and on native because the runner below is a loop.
    private var created = 0

    /**
     * Every behaviour in the contract, in a stable order, each paired with the name it fails under.
     *
     * The JVM wrapper does not use this — it declares one `@Test` per behaviour so a failure names
     * itself in the report. A Kotlin/Native test binary cannot register tests at runtime, so it walks
     * this list instead.
     */
    public val cases: List<Pair<String, suspend () -> Unit>> = listOf(
        case("a collection can be created, found, described and deleted") { collectionLifecycle() },
        case("a named-vectors collection reports what it was created with") { namedVectorsConfig() },
        case("ensureCollection creates once and then accepts what it finds") { ensureCollectionIsIdempotent() },
        case("updateCollection is accepted and leaves the collection serving") { updateCollectionKeepsItServing() },
        case("points upserted from a list come back by id with payload and vector") { upsertFromList() },
        case("a cosine collection stores the vector normalized") { cosineNormalizesOnWrite() },
        case("a uuid id and a numeric id are two different points") { uuidAndNumericIdsAreDistinct() },
        case("points upserted from a flow are all stored") { upsertFromFlow() },
        case("count honours a filter, and an unmatched filter counts zero") { countHonoursAFilter() },
        case("points can be deleted by id and by filter") { deleteByIdAndByFilter() },
        case("search returns the nearest point first, with the payload asked for") { searchRanksNearestFirst() },
        case("a search filter narrows the candidates, not the page") { searchFilterNarrowsCandidates() },
        case("withPayload selects fields, withVector decides on vectors") { withPayloadAndWithVector() },
        case("searchBatch answers each query in the order they were given") { searchBatchKeepsOrder() },
        case("searchGroups groups the hits by a payload field") { searchGroupsByPayloadField() },
        case("scroll emits every point exactly once across pages") { scrollEmitsEachPointOnce() },
        case("a filtered scroll returns only the matching points") { filteredScroll() },
        case("an ordered scroll comes back in the order it asked for") { orderedScroll() },
        case("setPayload merges, overwritePayload replaces, the rest remove") { payloadMutations() },
        case("named vectors can be updated and deleted one at a time") { namedVectorMutations() },
        case("a payload index can be created and dropped") { payloadIndexLifecycle() },
        case("batchUpdate applies its operations in order") { batchUpdateIsOrdered() },
        case("the filter clauses combine the way Qdrant combines them") { filterClausesCombine() },
        case("every matcher the DSL offers reaches the server") { everyMatcherReachesTheServer() },
        case("an alias can be created, listed, renamed and dropped") { aliasLifecycle() },
        case("facet counts the distinct values of a payload field") { facetCountsDistinctValues() },
        case("the distance matrix comes back in both of its forms") { distanceMatrixBothForms() },
        case("a running node reports itself healthy, ready and alive") { healthProbes() },
        case("cluster info describes the collection's shards") { clusterInfoDescribesShards() },
        case("a collection snapshot can be created, listed and deleted") { collectionSnapshotLifecycle() },
        case("a whole-storage snapshot can be created, listed and deleted") { storageSnapshotLifecycle() },
        case("an operation on a missing collection reports it as such") { missingCollectionIsReported() },
    )

    private fun case(name: String, run: suspend () -> Unit): Pair<String, suspend () -> Unit> = name to run

    // --- Collections -------------------------------------------------------------------------

    public suspend fun collectionLifecycle() {
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

    public suspend fun namedVectorsConfig() {
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

    public suspend fun ensureCollectionIsIdempotent() {
        val name = nextName()
        try {
            assertTrue(client.ensureCollection(name) { vector { size = 4; distance = Distance.COSINE } })
            assertFalse(client.ensureCollection(name) { vector { size = 4; distance = Distance.COSINE } })
        } finally {
            drop(name)
        }
    }

    public suspend fun updateCollectionKeepsItServing() {
        withCollection { name ->
            client.updateCollection(name) { optimizers = OptimizersConfig(indexingThreshold = 30_000) }

            assertEquals(CollectionStatus.GREEN, client.getCollection(name).status)
        }
    }

    // --- Points ------------------------------------------------------------------------------

    public suspend fun upsertFromList() {
        // Dot rather than cosine: a cosine collection stores the unit vector, which is Qdrant's
        // behaviour and not the client's, and would make this assert normalization instead of a round
        // trip. `cosineNormalizesOnWrite` pins that separately.
        withCollection(create = { vector { size = 4; distance = Distance.DOT } }) { name ->
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

    public suspend fun cosineNormalizesOnWrite() {
        // Qdrant normalizes on write for cosine, because the distance only depends on direction. It is
        // the server's behaviour rather than the client's, and it is the reason a vector read back from
        // a cosine collection does not equal the one that went in.
        withCollection { name ->
            client.upsert(name, wait = true) { point(1) { vector(0.0f, 0.0f, 0.0f, 2.0f) } }

            val stored = client.retrieve(name, listOf(PointId.num(1)), withVector = true).single().vector

            assertEquals(listOf(0.0f, 0.0f, 0.0f, 1.0f), denseOf(stored))
        }
    }

    public suspend fun uuidAndNumericIdsAreDistinct() {
        withCollection { name ->
            client.upsert(name, wait = true) {
                point(1) { vector(0.1f, 0.2f, 0.3f, 0.4f) }
                point(UUID) { vector(0.1f, 0.2f, 0.3f, 0.4f) }
            }

            val ids = client.retrieve(name, listOf(PointId.num(1), PointId.uuid(UUID))).map { it.id }.toSet()

            assertEquals(setOf(PointId.num(1), PointId.uuid(UUID)), ids)
        }
    }

    public suspend fun upsertFromFlow() {
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

    public suspend fun countHonoursAFilter() {
        withCollection { name ->
            seed(name)

            assertEquals(3L, client.count(name))
            assertEquals(2L, client.count(name) { must { "lang" eq "it" } })
            assertEquals(0L, client.count(name) { must { "lang" eq "de" } })
        }
    }

    public suspend fun deleteByIdAndByFilter() {
        withCollection { name ->
            seed(name)

            client.delete(name, listOf(PointId.num(1)), wait = true)
            assertEquals(2L, client.count(name))

            client.delete(name, wait = true) { must { "lang" eq "it" } }
            assertEquals(1L, client.count(name))
        }
    }

    // --- Search ------------------------------------------------------------------------------

    public suspend fun searchRanksNearestFirst() {
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

    public suspend fun searchFilterNarrowsCandidates() {
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

    public suspend fun withPayloadAndWithVector() {
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

    public suspend fun searchBatchKeepsOrder() {
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

    public suspend fun searchGroupsByPayloadField() {
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

    public suspend fun scrollEmitsEachPointOnce() {
        withCollection { name ->
            client.upsert(name, wait = true) {
                for (id in 1L..30L) point(id) { vector(0.1f, 0.2f, 0.3f, id / 100f) }
            }

            val ids = client.scroll(name, pageSize = 7).map { it.id }.toList()

            assertEquals(30, ids.size)
            assertEquals(30, ids.toSet().size, "a point was emitted twice")
        }
    }

    public suspend fun filteredScroll() {
        withCollection { name ->
            seed(name)

            val ids = client.scroll(name, pageSize = 2) { filter { must { "lang" eq "it" } } }.map { it.id }.toList()

            assertEquals(listOf(PointId.num(1), PointId.num(2)), ids.sortedBy { (it as PointId.Num).value })
        }
    }

    public suspend fun orderedScroll() {
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

    public suspend fun payloadMutations() {
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

    public suspend fun namedVectorMutations() {
        withCollection(
            create = {
                namedVector("text") { size = 2; distance = Distance.DOT }
                namedVector("image") { size = 2; distance = Distance.DOT }
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

    public suspend fun payloadIndexLifecycle() {
        withCollection { name ->
            seed(name)

            client.createPayloadIndex(name, "lang", PayloadSchemaType.KEYWORD, wait = true)
            assertTrue(client.getCollection(name).payloadSchema.containsKey("lang"))

            client.deletePayloadIndex(name, "lang", wait = true)
            assertFalse(client.getCollection(name).payloadSchema.containsKey("lang"))
        }
    }

    public suspend fun batchUpdateIsOrdered() {
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

    public suspend fun filterClausesCombine() {
        withCollection { name ->
            seed(name)

            assertEquals(2L, client.count(name) { must { "lang" eq "it"; "year" gte 2023 } })
            assertEquals(3L, client.count(name) { should { "lang" eq "it"; "lang" eq "en" } })
            assertEquals(1L, client.count(name) { mustNot { "lang" eq "it" } })
            assertEquals(3L, client.count(name) { minShould(1) { "lang" eq "it"; "year" gte 2020 } })
        }
    }

    public suspend fun everyMatcherReachesTheServer() {
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
            // matchPhrase is deliberately absent. Qdrant matches a phrase only against a text index
            // created with `phrase_matching: true`, and `createPayloadIndex` cannot ask for that yet,
            // so the filter is accepted and matches nothing. Asserting zero here would pin the gap as
            // if it were the behaviour.
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

    public suspend fun aliasLifecycle() {
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

    public suspend fun facetCountsDistinctValues() {
        withCollection { name ->
            seed(name)

            client.createPayloadIndex(name, "lang", PayloadSchemaType.KEYWORD, wait = true)
            val hits = client.facet(name, key = "lang", exact = true).associate { it.value to it.count }

            assertEquals(2L, hits[FacetValue.StringValue("it")])
            assertEquals(1L, hits[FacetValue.StringValue("en")])
        }
    }

    public suspend fun distanceMatrixBothForms() {
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

    public suspend fun healthProbes() {
        assertTrue(client.healthz(), "healthz")
        assertTrue(client.readyz(), "readyz")
        assertTrue(client.livez(), "livez")
    }

    public suspend fun clusterInfoDescribesShards() {
        withCollection { name ->
            val info = client.collectionClusterInfo(name)

            assertTrue(info.localShards.isNotEmpty() || info.remoteShards.isNotEmpty(), "no shard reported")
        }
    }

    // --- Snapshots ---------------------------------------------------------------------------

    public suspend fun collectionSnapshotLifecycle() {
        withCollection { name ->
            seed(name)

            val snapshot = client.createSnapshot(name)
            assertTrue(client.listSnapshots(name).any { it.name == snapshot.name })

            client.deleteSnapshot(name, snapshot.name)
            assertTrue(client.listSnapshots(name).none { it.name == snapshot.name })
        }
    }

    public suspend fun storageSnapshotLifecycle() {
        withCollection { name ->
            seed(name)

            val snapshot = client.createStorageSnapshot()
            assertTrue(client.listStorageSnapshots().any { it.name == snapshot.name })

            client.deleteStorageSnapshot(snapshot.name)
            assertTrue(client.listStorageSnapshots().none { it.name == snapshot.name })
        }
    }

    // --- Failures ----------------------------------------------------------------------------

    public suspend fun missingCollectionIsReported() {
        val missing = "no-such-collection-${++created}"

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

    private fun nextName(): String = "$namePrefix-${++created}"

    private suspend fun drop(name: String) {
        runCatching { client.deleteCollection(name) }
    }

    /**
     * Runs [block] against a collection of its own. Every case gets a fresh name, so a failure leaves
     * nothing behind for the next one to trip over and they do not have to run in order.
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
        const val UUID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
