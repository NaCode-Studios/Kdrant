package dev.kdrant.testkit

import dev.kdrant.IngestCheckpoint
import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.payloadOf
import dev.kdrant.ingest
import dev.kdrant.model.CollectionStatus
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Direction
import dev.kdrant.model.Distance
import dev.kdrant.model.FacetValue
import dev.kdrant.model.Modifier
import dev.kdrant.model.MultiVectorComparator
import dev.kdrant.model.OptimizersConfig
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.Tokenizer
import dev.kdrant.model.VectorData
import dev.kdrant.model.VectorsConfig
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
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
// One class on purpose. It is the single statement of what an engine has to do, and splitting it by
// topic would mean a reader asking "what is the contract" has four files to open and no guarantee the
// four agree. It grows when the contract grows, which is the intended shape.
@Suppress("LargeClass", "TooManyFunctions")
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
        case("a sparse vector round-trips and answers a sparse query") { sparseVectors() },
        case("an IDF sparse collection scores by rarity, not by the value sent") { sparseIdfIsAppliedByTheServer() },
        case("hybrid search fuses a dense and a sparse ranking") { hybridSearchFusesBothRankings() },
        case("a multi-vector collection stores and scores late interaction") { multiVectors() },
        case("a query naming a vector the collection lacks is refused") { unknownVectorNameIsRefused() },
        case("scroll emits every point exactly once across pages") { scrollEmitsEachPointOnce() },
        case("a filtered scroll returns only the matching points") { filteredScroll() },
        case("an ordered scroll comes back in the order it asked for") { orderedScroll() },
        case("setPayload merges, overwritePayload replaces, the rest remove") { payloadMutations() },
        case("named vectors can be updated and deleted one at a time") { namedVectorMutations() },
        case("a payload index can be created and dropped") { payloadIndexLifecycle() },
        case("a payload index takes the parameters its type accepts") { payloadIndexParameters() },
        case("batchUpdate applies its operations in order") { batchUpdateIsOrdered() },
        case("an ingest killed partway resumes from its token") { ingestResumesFromItsToken() },
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

    // --- Sparse, multi-vector and hybrid ------------------------------------------------------

    /**
     * Sparse vectors were in the public API for four releases with nothing but body assertions behind
     * them. A body assertion cannot see that Qdrant returns a sparse vector with its indices sorted,
     * which is not the order they were sent in and is what a caller comparing the two would trip over.
     */
    public suspend fun sparseVectors() {
        withCollection(create = { sparseVector("terms") }) { name ->
            client.upsert(name, wait = true) {
                point(1) {
                    vector(VectorData.Named(mapOf("terms" to VectorData.Sparse(listOf(7, 2), listOf(0.9f, 0.4f)))))
                }
                point(2) { vector(VectorData.Named(mapOf("terms" to VectorData.Sparse(listOf(3), listOf(0.8f))))) }
            }

            val stored = client.retrieve(name, listOf(PointId.num(1)), withVector = true).single().vector
            val sparse = (stored as VectorData.Named).vectors.getValue("terms") as VectorData.Sparse
            assertEquals(setOf(2, 7), sparse.indices.toSet())
            assertEquals(2, sparse.values.size)

            val hits = client.search(name) {
                querySparse(indices = listOf(7), values = listOf(1.0f))
                using = "terms"
                limit = 5
            }

            assertEquals(listOf(PointId.num(1)), hits.map { it.id }, "only the point sharing index 7 matches")
        }
    }

    /**
     * With `Modifier.IDF` the server rescales a sparse value by how rare its index is across the
     * collection, so the score is not the dot product of what was sent. A serialization test predicts
     * the request; only a server can produce this.
     */
    public suspend fun sparseIdfIsAppliedByTheServer() {
        withCollection(create = { sparseVector("terms") { modifier = Modifier.IDF } }) { name ->
            // Index 1 is in every point and index 9 in one, so index 9 is the rare, informative term.
            client.upsert(name, wait = true) {
                point(1) {
                    vector(VectorData.Named(mapOf("terms" to VectorData.Sparse(listOf(1, 9), listOf(1.0f, 1.0f)))))
                }
                point(2) { vector(VectorData.Named(mapOf("terms" to VectorData.Sparse(listOf(1), listOf(1.0f))))) }
                point(3) { vector(VectorData.Named(mapOf("terms" to VectorData.Sparse(listOf(1), listOf(1.0f))))) }
            }

            val hits = client.search(name) {
                querySparse(indices = listOf(1, 9), values = listOf(1.0f, 1.0f))
                using = "terms"
                limit = 3
            }

            assertEquals(PointId.num(1), hits.first().id, "the point carrying the rare term ranks first")
            assertTrue(
                hits.first().score > hits.last().score,
                "IDF should separate the scores; got ${hits.map { it.score }}",
            )
        }
    }

    /**
     * Reciprocal-rank fusion over a dense prefetch and a sparse one: the single most common thing built
     * on Qdrant, and the one path this client advertised with no end-to-end coverage at all.
     */
    public suspend fun hybridSearchFusesBothRankings() {
        withCollection(
            create = {
                namedVector("dense") { size = 4; distance = Distance.DOT }
                sparseVector("terms")
            },
        ) { name ->
            // Point 1 wins on dense and loses on sparse; point 2 the other way round. Neither ranking
            // alone puts them in the fused order, which is what makes the fusion visible.
            client.upsert(name, wait = true) {
                point(1) {
                    vector(
                        VectorData.Named(
                            mapOf(
                                "dense" to VectorData.Dense(listOf(1.0f, 0.0f, 0.0f, 0.0f)),
                                "terms" to VectorData.Sparse(listOf(5), listOf(0.1f)),
                            ),
                        ),
                    )
                }
                point(2) {
                    vector(
                        VectorData.Named(
                            mapOf(
                                "dense" to VectorData.Dense(listOf(0.0f, 1.0f, 0.0f, 0.0f)),
                                "terms" to VectorData.Sparse(listOf(5), listOf(0.9f)),
                            ),
                        ),
                    )
                }
                point(3) {
                    vector(VectorData.Named(mapOf("dense" to VectorData.Dense(listOf(0.0f, 0.0f, 1.0f, 0.0f)))))
                }
            }

            val fused = client.search(name) {
                prefetch { query(listOf(1.0f, 0.0f, 0.0f, 0.0f)); using = "dense"; limit = 10 }
                prefetch { querySparse(listOf(5), listOf(1.0f)); using = "terms"; limit = 10 }
                rrf()
                limit = 10
            }

            val ids = fused.map { it.id }
            assertEquals(setOf(PointId.num(1), PointId.num(2)), ids.take(2).toSet())
            assertTrue(PointId.num(3) in ids, "the dense-only point should still be fused in, ranked last")
            assertEquals(PointId.num(3), ids.last())

            // dbsf is the other fusion the DSL offers, and it has never met a server either.
            val dbsf = client.search(name) {
                prefetch { query(listOf(1.0f, 0.0f, 0.0f, 0.0f)); using = "dense"; limit = 10 }
                prefetch { querySparse(listOf(5), listOf(1.0f)); using = "terms"; limit = 10 }
                dbsf()
                limit = 10
            }
            assertTrue(dbsf.isNotEmpty(), "distribution-based fusion returned nothing")
        }
    }

    /**
     * A multi-vector collection carries a comparator and refuses points whose inner vectors disagree on
     * length. Both are server behaviours a request-shape test cannot reach.
     */
    public suspend fun multiVectors() {
        withCollection(
            create = {
                namedVector("colbert") {
                    size = 2
                    distance = Distance.DOT
                    multivector = MultiVectorComparator.MAX_SIM
                }
            },
        ) { name ->
            client.upsert(name, wait = true) {
                point(1) {
                    vector(
                        VectorData.Named(
                            mapOf("colbert" to VectorData.MultiDense(listOf(listOf(1.0f, 0.0f), listOf(0.9f, 0.1f)))),
                        ),
                    )
                }
                point(2) {
                    vector(
                        VectorData.Named(
                            mapOf("colbert" to VectorData.MultiDense(listOf(listOf(0.0f, 1.0f)))),
                        ),
                    )
                }
            }

            val hits = client.search(name) {
                queryMulti(listOf(listOf(1.0f, 0.0f)))
                using = "colbert"
                limit = 2
            }

            assertEquals(PointId.num(1), hits.first().id)

            val stored = client.retrieve(name, listOf(PointId.num(1)), withVector = true).single().vector
            val multi = (stored as VectorData.Named).vectors.getValue("colbert")
            assertTrue(multi is VectorData.MultiDense, "a multi-vector came back as ${multi::class.simpleName}")
            assertEquals(2, multi.vectors.size)

            // The inner vectors have to agree on length. The server is what enforces that.
            val ragged = runCatching {
                client.upsert(name, wait = true) {
                    point(3) {
                        vector(
                            VectorData.Named(
                                mapOf(
                                    "colbert" to VectorData.MultiDense(
                                        listOf(listOf(1.0f, 0.0f), listOf(1.0f, 0.0f, 0.0f)),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }.exceptionOrNull()
            assertTrue(
                ragged is KdrantException,
                "a ragged multi-vector should be refused, got ${ragged?.let { it::class.simpleName }}",
            )
        }
    }

    /** A query naming a vector the collection does not have fails, rather than silently searching another. */
    public suspend fun unknownVectorNameIsRefused() {
        withCollection(create = { namedVector("dense") { size = 4; distance = Distance.DOT } }) { name ->
            val error = runCatching {
                client.search(name) {
                    querySparse(indices = listOf(1), values = listOf(1.0f))
                    using = "no-such-vector"
                    limit = 1
                }
            }.exceptionOrNull()

            assertTrue(
                error is KdrantException,
                "expected a KdrantException, got ${error?.let { it::class.simpleName }}",
            )
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

    public suspend fun payloadIndexParameters() {
        withCollection { name ->
            seed(name)

            // Every parameter the builder can send, across the four types that take different ones.
            // The assertion is that the server accepted them and built the index it was asked for:
            // `on_disk` and `is_tenant` change the layout rather than the answers, so a behavioural
            // assertion about them would be an assertion about Qdrant's storage internals.
            client.createPayloadIndex(name, "lang", wait = true) { keyword { isTenant = true; onDisk = true } }
            client.createPayloadIndex(name, "year", wait = true) {
                integer { lookup = true; range = true; isPrincipal = false; onDisk = true }
            }

            val schema = client.getCollection(name).payloadSchema
            assertEquals("keyword", schema["lang"]?.dataType)
            assertEquals("integer", schema["year"]?.dataType)

            // The index still answers the filters it was built for.
            assertEquals(2L, client.count(name) { must { "lang" eq "it" } })
            assertEquals(2L, client.count(name) { must { "year" gte 2023 } })
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

    // --- Ingest ------------------------------------------------------------------------------

    /**
     * The measurement that decides whether the ingest worked is not throughput. It is what the process
     * does when it is killed at an arbitrary point and started again, which before this was start over.
     *
     * The source is generated rather than materialized, which is the shape a source larger than memory
     * has: nothing here holds more than a batch at a time, and the same call would run against a
     * hundred-million-point file unchanged.
     */
    public suspend fun ingestResumesFromItsToken() {
        withCollection { name ->
            val total = 500L
            var token: IngestCheckpoint? = null

            // The first run dies partway, the way a process does: mid-stream and without warning.
            val crashed = runCatching {
                client.ingest(
                    name,
                    generatedPoints(total, failAfter = 220),
                    batchSize = 50,
                    concurrency = 2,
                    onCheckpoint = { token = it },
                )
            }.exceptionOrNull()

            assertNotNull(crashed, "the source was supposed to fail partway")
            val checkpoint = assertNotNull(token, "no checkpoint was ever handed out")
            assertTrue(checkpoint.acknowledgedPoints > 0, "nothing was acknowledged before the crash")
            assertTrue(checkpoint.acknowledgedPoints < total, "the crash happened after everything was written")

            // The token is a lower bound on what the collection holds, never an upper one. A batch that
            // was in flight when the run died may already have been applied, and an acknowledgement
            // that never came back cannot move a checkpoint. Resuming therefore re-sends a few points
            // that are already there, which upsert makes free; the opposite would skip points and lose
            // them without a trace.
            val stored = client.count(name)
            assertTrue(
                stored >= checkpoint.acknowledgedPoints,
                "the token claimed ${checkpoint.acknowledgedPoints} points and the collection holds " +
                    "$stored: a token that over-claims skips points on resume",
            )
            assertTrue(stored < total, "the run was supposed to die before writing everything")

            var produced = 0L
            val report = client.ingest(
                name,
                generatedPoints(total, onEmit = { produced++ }),
                batchSize = 50,
                concurrency = 2,
                resumeFrom = checkpoint,
            )

            assertEquals(total, client.count(name), "the resumed run should have completed the collection")
            assertEquals(total, report.checkpoint.acknowledgedPoints, "the token has to be absolute, not per run")

            // Batches, not emissions, is what "without re-sending" means. Resuming skips the acknowledged
            // points on the way out, so the resumed run makes fewer requests than a full one would: 500
            // points in batches of 50 is ten requests from cold, and fewer from a token.
            val batchesFromCold = (total / 50).toInt()
            assertTrue(
                report.batches < batchesFromCold,
                "a resumed run sent ${report.batches} batches, the same as starting over ($batchesFromCold)",
            )

            // And the source is still asked for all of them, which is the part that surprises people:
            // `resumeFrom` drops the acknowledged points as they arrive rather than telling the source
            // to skip them. Nothing goes over the network twice; the source is still read twice.
            assertEquals(total, produced, "the source is replayed in full and the prefix is dropped on the way out")
        }
    }

    /**
     * A lazily generated source. [failAfter] makes it stop like a process being killed; [onEmit] counts
     * what actually left the source, which is how the resumed run is shown not to re-send.
     */
    private fun generatedPoints(
        count: Long,
        failAfter: Long? = null,
        onEmit: (Long) -> Unit = {},
    ): Flow<PointStruct> = flow {
        for (id in 1L..count) {
            if (failAfter != null && id > failAfter) error("the source died at point $id")
            onEmit(id)
            emit(
                PointStruct(
                    id = PointId.num(id),
                    vector = VectorData.Dense(listOf(0.1f, 0.2f, 0.3f, id / count.toFloat())),
                ),
            )
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
            // Phrase matching is not free — it stores token positions — so Qdrant only honours
            // matchPhrase against an index that asked for it.
            client.createPayloadIndex(name, "title", wait = true) {
                text { tokenizer = Tokenizer.WORD; phraseMatching = true }
            }
            client.createPayloadIndex(name, "at", PayloadSchemaType.DATETIME, wait = true)

            assertEquals(1L, client.count(name) { must { "lang" eq "it" } })
            assertEquals(1L, client.count(name) { must { matchAny("lang", "it", "en") } })
            assertEquals(1L, client.count(name) { must { matchExcept("lang", "de") } })
            assertEquals(1L, client.count(name) { must { matchText("title", "vector Kotlin") } })
            assertEquals(1L, client.count(name) { must { matchTextAny("title", "vector rust") } })
            assertEquals(1L, client.count(name) { must { matchPhrase("title", "vector database") } })
            // The phrase is what separates this from matchText: both words are present, and in the
            // other order, so a phrase match has to miss where a token match would hit.
            assertEquals(0L, client.count(name) { must { matchPhrase("title", "database vector") } })
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

    // --- Server-side inference -----------------------------------------------------------------

    /**
     * A document upserted and a document queried, with the embedding done by the server.
     *
     * Deliberately not in [cases]. Inference needs a provider configured on the Qdrant under test, and a
     * plain container has none, so this runs where one exists and is skipped where it does not. The
     * request shape is held everywhere else: the REST engine's contract test validates both bodies
     * against Qdrant's own OpenAPI document on every build.
     *
     * @param model a model the server's provider offers.
     * @param size the dimensionality that model produces, which the collection has to be created with.
     */
    public suspend fun inferenceRoundTrip(model: String, size: Long) {
        withCollection(create = { vector { this.size = size; distance = Distance.COSINE } }) { name ->
            client.upsert(name, wait = true) {
                point(1) { document("Kotlin is a language for the JVM and beyond", model = model) }
                point(2) { document("a recipe for tomato sauce", model = model) }
            }

            val hits = client.search(name) {
                queryDocument("which language runs on the JVM", model = model)
                limit = 1
            }

            assertEquals(PointId.num(1), hits.single().id, "the server embedded both sides and ranked them")
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
