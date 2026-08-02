package dev.kdrant.migrate

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.payloadOf
import dev.kdrant.model.Distance
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.Record
import dev.kdrant.model.VectorData
import dev.kdrant.transport.rest.Kdrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory

/**
 * M42 against a real Qdrant: the migration this module exists to stop people writing badly.
 *
 * Two properties are what make it worth shipping, and neither can be asserted without a server. A
 * reader querying through the alias must not see an error or an empty page at any point in the
 * migration, including the moment the alias moves. And a migration killed halfway must finish on a
 * second run without dropping a point or writing one twice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectionMigrationIntegrationTest {

    private lateinit var container: QdrantContainer
    private lateinit var client: QdrantClient

    @BeforeAll
    fun startQdrant() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the migration integration test",
        )
        container = QdrantContainer(IMAGE).also { it.start() }
        client = Kdrant(host = container.host, port = container.getMappedPort(6333))
    }

    @AfterAll
    fun stopQdrant() {
        if (::client.isInitialized) client.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    @Test
    fun `a reader querying through the alias sees no error and no empty result`() = runBlocking {
        val source = "reads-768"
        val target = "reads-1536"
        val alias = "reads"
        seed(source, POINTS)
        client.updateAliases { createAlias(collection = source, alias = alias) }

        val failures = mutableListOf<String>()
        val migrating = AtomicBoolean(true)

        coroutineScope {
            val reader = async(Dispatchers.IO) { readThroughout(alias, migrating, failures) }

            val report = client.migrateCollection(
                from = source,
                to = target,
                alias = alias,
                createTarget = { vector { size = WIDE; distance = Distance.COSINE } },
                batchSize = 64,
                transform = ::widen,
            )
            migrating.set(false)
            reader.await()

            assertEquals(POINTS.toLong(), report.copied)
            assertEquals(report.sourceCount, report.targetCount)
            assertTrue(report.aliasMoved, "the alias should have moved once the copy checked out")
            assertEquals(1.0, report.recall, "widening a vector keeps every neighbour, so recall is exact")
        }

        assertTrue(failures.isEmpty(), "the reader saw ${failures.size} failures: ${failures.take(3)}")
        // The alias now resolves to the new collection, and the old one is still there to fall back to.
        assertEquals(listOf(alias), client.listCollectionAliases(target).map { it.aliasName })
        assertEquals(POINTS.toLong(), client.count(source))
    }

    @Test
    fun `a migration killed halfway finishes on the second run, with nothing dropped or duplicated`() =
        runBlocking {
            val source = "resume-768"
            val target = "resume-1536"
            seed(source, POINTS)
            val checkpoints = FileMigrationCheckpointStore(createTempDirectory("kdrant-migration"))

            var seen = 0
            val interrupted = runCatching {
                client.migrateCollection(
                    from = source,
                    to = target,
                    createTarget = { vector { size = WIDE; distance = Distance.COSINE } },
                    batchSize = 64,
                    checkpoints = checkpoints,
                ) { record ->
                    // Die well past the first batch and well before the last, the way a pod gets evicted.
                    if (++seen > POINTS / 2) error("the migration process went away")
                    widen(record)
                }
            }
            assertTrue(interrupted.isFailure, "the first run was supposed to be interrupted")

            val partial = client.count(target)
            assertTrue(partial in 1 until POINTS.toLong(), "expected a partial copy, got $partial of $POINTS")
            assertNotEquals(null, checkpoints.load("$source->$target"), "the interrupted run left no cursor")

            val report = client.migrateCollection(
                from = source,
                to = target,
                createTarget = { vector { size = WIDE; distance = Distance.COSINE } },
                batchSize = 64,
                checkpoints = checkpoints,
                transform = ::widen,
            )

            assertTrue(report.resumed, "the second run should have picked up the cursor")
            assertTrue(report.copied < POINTS, "the second run re-copied everything instead of resuming")
            assertEquals(POINTS.toLong(), report.targetCount, "a point was dropped or written twice")
            assertEquals(report.sourceCount, report.targetCount)
        }

    @Test
    fun `a copy that lost points is refused, and the alias stays where it was`() = runBlocking {
        val source = "refused-768"
        val target = "refused-1536"
        val alias = "refused"
        seed(source, 64)
        client.updateAliases { createAlias(collection = source, alias = alias) }

        val error = runCatching {
            client.migrateCollection(
                from = source,
                to = target,
                alias = alias,
                createTarget = { vector { size = WIDE; distance = Distance.COSINE } },
                batchSize = 16,
            ) { record ->
                // Half the points quietly do not make it, which is the failure a count check is for.
                if ((record.id as PointId.Num).value % 2UL == 0UL) null else widen(record)
            }
        }.exceptionOrNull()

        assertTrue(
            error is MigrationVerificationFailed,
            "expected MigrationVerificationFailed, got ${error?.let { it::class.simpleName }}",
        )
        val failed = error as MigrationVerificationFailed
        assertEquals(64L, failed.report.sourceCount)
        assertEquals(32L, failed.report.targetCount)
        assertTrue(!failed.report.aliasMoved)
        assertEquals(listOf(alias), client.listCollectionAliases(source).map { it.aliasName })
    }

    /**
     * Queries through the alias for as long as the migration runs, recording anything that goes wrong.
     *
     * By point id rather than by vector, and that is not a convenience. The whole reason for migrating
     * is that the vector size changes, so a reader holding a 4-dimension query vector is refused by the
     * new collection the moment the alias moves — correctly, and by Qdrant, and it is the caller's job
     * to deploy the new embedding model alongside. What the migration is responsible for is that the
     * alias resolves to a collection holding the data at every instant, which is what a query by id
     * asks and what a query by vector cannot separate from the dimension change.
     */
    private suspend fun CoroutineScope.readThroughout(
        alias: String,
        migrating: AtomicBoolean,
        failures: MutableList<String>,
    ) = withContext(Dispatchers.IO) {
        var reads = 0
        while (migrating.get() && isActive) {
            val hits = runCatching { client.search(alias) { query(PointId.num(1)); limit = 5 } }
            hits.fold(
                onSuccess = { if (it.isEmpty()) failures += "empty result on read $reads" },
                onFailure = { failures += "${it::class.simpleName}: ${it.message}" },
            )
            reads++
        }
        assertTrue(reads > 0, "the reader never got a query in")
    }

    private suspend fun seed(collection: String, points: Int) {
        client.createCollection(collection) { vector { size = NARROW; distance = Distance.COSINE } }
        client.upsert(
            collection,
            (1..points).asSequence().map { id ->
                PointStruct(
                    id = PointId.num(id.toLong()),
                    vector = VectorData.Dense(narrow(id)),
                    payload = payloadOf("n" to id),
                )
            },
            wait = true,
        )
    }

    private companion object {
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.18.2"
        const val POINTS = 400
        const val NARROW = 4L
        const val WIDE = 8L

        /** A point's vector: distinct enough that its neighbours are unambiguous. */
        fun narrow(id: Int): List<Float> {
            val angle = id.toFloat() / 97f
            return listOf(angle, 1f - angle, angle * angle, 1f)
        }


        /**
         * The stand-in for a re-embedding: the same vector in a wider space. Cosine similarity is
         * unchanged by duplicating every component, so the neighbour check should come back exact —
         * which is what makes a recall below 1.0 here a real failure rather than model drift.
         */
        fun widen(record: Record): PointStruct? {
            val values = (record.vector as? VectorData.Dense)?.values ?: return null
            return PointStruct(record.id, VectorData.Dense(values + values), record.payload)
        }
    }
}
