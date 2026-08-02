package dev.kdrant.migrate

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.Record
import dev.kdrant.model.VectorData
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * Copies a collection into another one and moves an alias onto it, without taking reads down.
 *
 * Every team running a vector database eventually changes embedding model, and a collection's vector
 * size is fixed at creation. The way out is known and nobody enjoys writing it: create a second
 * collection with the new configuration, re-embed and copy the points, verify, swap an alias so readers
 * move in one step, keep the old collection until you are sure. Kdrant already had every call this
 * needs; what it did not have was the procedure, so each user rebuilt it, and the parts that are easy
 * to get wrong were the parts they got wrong.
 *
 * ```kotlin
 * val report = qdrant.migrateCollection(
 *     from = "docs-768",
 *     to = "docs-1536",
 *     alias = "docs",
 *     createTarget = { vector { size = 1536; distance = Distance.COSINE } },
 *     checkpoints = FileMigrationCheckpointStore(Path("/var/lib/migrations")),
 * ) { record ->
 *     PointStruct(record.id, VectorData.Dense(embed(record.payload!!["text"]!!.text())), record.payload)
 * }
 * ```
 *
 * Readers go through the alias throughout and never see a gap: the alias points at the old collection
 * until the last moment and at the new one after it, and Qdrant applies the change in one atomic
 * request.
 *
 * ### Two properties, and why each is here
 *
 * **It resumes.** A copy of ten million points will be interrupted, and starting over is not an answer.
 * After every batch Qdrant acknowledges, the id reached goes to [checkpoints]; the next run reads it
 * and continues. Nothing is dropped, because the checkpoint is written after the write rather than
 * before it, and nothing is duplicated, because a point is written under the id it already had.
 *
 * **The alias swap is gated on a verification that ran.** Counts have to match, and a sample of
 * queries has to return the same neighbours from both collections above a stated recall. If the check
 * fails the alias does not move and [MigrationVerificationFailed] is thrown with the numbers in it.
 *
 * ### What it does not do
 *
 * It does not stop writes to the source. A point written to the source behind the cursor — with an id
 * the copy has already passed — is not picked up, and the count check will notice. Migrate from a
 * source that is being read rather than written, or run the migration twice: the second pass over an
 * already-copied collection is cheap and catches whatever the first one raced.
 *
 * @param from the collection to read.
 * @param to the collection to write. Created by [createTarget] when that is given.
 * @param alias moved onto [to] once the verification passes. `null` performs the copy and the check
 *   and leaves the alias alone, which is how you rehearse one.
 * @param createTarget the configuration for [to]. `null` expects it to exist already.
 * @param batchSize points per read page and per upsert.
 * @param checkpoints where progress is remembered. The default survives nothing; see
 *   [MigrationCheckpointStore].
 * @param migrationId the key progress is stored under. The default is derived from the two collection
 *   names, which is right unless you run the same pair twice for different reasons.
 * @param verification how hard to check before moving the alias.
 * @param transform what to write for each source point. The default copies it unchanged, which is a
 *   re-shard or a config change rather than a re-embedding; return `null` to drop a point.
 * @throws MigrationVerificationFailed if the copy does not check out. The alias is untouched.
 */
public suspend fun QdrantClient.migrateCollection(
    from: String,
    to: String,
    alias: String? = null,
    createTarget: (CreateCollectionBuilder.() -> Unit)? = null,
    batchSize: Int = 256,
    checkpoints: MigrationCheckpointStore = MigrationCheckpointStore.inMemory(),
    migrationId: String = "$from->$to",
    verification: MigrationVerification = MigrationVerification(),
    transform: suspend (Record) -> PointStruct? = ::copyUnchanged,
): MigrationReport {
    require(from != to) { "a migration reads one collection and writes another, and both are '$from'" }
    require(batchSize > 0) { "batchSize must be > 0, was $batchSize" }

    createTarget?.let { ensureCollection(to, it) }
    check(collectionExists(to)) {
        "the target collection '$to' does not exist. Pass createTarget = { ... } to have the migration " +
            "create it, or create it yourself first."
    }

    val resumeFrom = checkpoints.load(migrationId)
    val copied = copyPoints(from, to, batchSize, resumeFrom, checkpoints, migrationId, transform)

    val report = verify(from, to, copied, resumeFrom, verification, aliasMoved = false)
    if (alias != null) moveAlias(alias, to)

    checkpoints.clear(migrationId)
    return if (alias == null) report else report.withAliasMoved()
}

/** The default [transform]: the same point, in the new collection. */
public fun copyUnchanged(record: Record): PointStruct? =
    record.vector?.let { PointStruct(record.id, it, record.payload) }

/**
 * Reads [from] in id order and writes to [to], recording the id reached after every acknowledged
 * batch. Returns how many points this run wrote.
 */
private suspend fun QdrantClient.copyPoints(
    from: String,
    to: String,
    batchSize: Int,
    resumeFrom: MigrationCheckpoint?,
    checkpoints: MigrationCheckpointStore,
    migrationId: String,
    transform: suspend (Record) -> PointStruct?,
): Long {
    var written = 0L
    val batch = ArrayList<PointStruct>(batchSize)
    var lastId: PointId? = null

    val source: Flow<Record> = scroll(from, pageSize = batchSize) {
        withPayload = WithPayload.All
        withVector = true
        startAt = resumeFrom?.resumeAt
    }

    suspend fun flush() {
        if (batch.isEmpty()) return
        // wait = true, so the checkpoint below records something Qdrant has actually stored. A
        // checkpoint written ahead of the write is how a migration drops the batch it crashed on.
        upsert(to, batch.toList().asSequence(), wait = true)
        written += batch.size
        batch.clear()
        lastId?.let { checkpoints.save(migrationId, MigrationCheckpoint(it, (resumeFrom?.copied ?: 0) + written)) }
    }

    source.collect { record ->
        transform(record)?.let { batch.add(it) }
        lastId = record.id
        if (batch.size >= batchSize) flush()
    }
    flush()

    return written
}

/** Compares the two collections and refuses the migration if they disagree. */
private suspend fun QdrantClient.verify(
    from: String,
    to: String,
    copied: Long,
    resumeFrom: MigrationCheckpoint?,
    verification: MigrationVerification,
    aliasMoved: Boolean,
): MigrationReport {
    val sourceCount = count(from)
    val targetCount = count(to)
    val countsAgree = sourceCount == targetCount

    // The neighbour sample costs one query per sampled point against each collection, so it is skipped
    // when the counts already say the copy is wrong: there is nothing left to learn from it.
    val agreement =
        if (countsAgree && verification.sampleSize > 0) neighbourAgreement(from, to, verification) else NONE

    val report = MigrationReport(
        source = from,
        target = to,
        copied = copied,
        resumed = resumeFrom != null,
        sourceCount = sourceCount,
        targetCount = targetCount,
        sampledPairs = agreement.compared,
        recall = agreement.recall,
        aliasMoved = aliasMoved,
    )

    refusal(from, to, sourceCount, targetCount, verification, agreement)?.let {
        throw MigrationVerificationFailed(it, report)
    }
    return report
}

/** Why the alias may not move, or `null` if it may. */
private fun refusal(
    from: String,
    to: String,
    sourceCount: Long,
    targetCount: Long,
    verification: MigrationVerification,
    agreement: NeighbourAgreement,
): String? = when {
    sourceCount != targetCount ->
        "'$from' holds $sourceCount points and '$to' holds $targetCount. The copy is incomplete, so the " +
            "alias was not moved."

    verification.sampleSize == 0 -> null

    agreement.compared == 0 ->
        "the neighbour check could not read a dense vector from '$from'. Name the vector with " +
            "MigrationVerification(using = ...), or set sampleSize = 0 to accept a count-only check."

    agreement.recall != null && agreement.recall < verification.minRecall ->
        "the two collections agree on ${percent(agreement.recall)} of neighbours over " +
            "${agreement.compared} sampled queries, below the ${percent(verification.minRecall)} asked " +
            "for. The alias was not moved and '$to' is still there to look at."

    else -> null
}

/** How much the two collections agree on, over the points the check could read a vector for. */
private class NeighbourAgreement(val compared: Int, val recall: Double?)

private val NONE = NeighbourAgreement(compared = 0, recall = null)

/**
 * Queries a sample of the source's points through both collections and measures how much of each
 * neighbourhood survived. This is the check counts cannot do: a copy where every point arrived and the
 * new embedding ranks them differently enough that search is no longer the search anyone tested.
 */
private suspend fun QdrantClient.neighbourAgreement(
    from: String,
    to: String,
    verification: MigrationVerification,
): NeighbourAgreement {
    val sample = scroll(from, pageSize = verification.sampleSize) {
        withVector = true
    }.take(verification.sampleSize).toList()

    var compared = 0
    var overlap = 0.0
    for (record in sample) {
        val (sourceVector, targetVector) = vectorPair(record, to, verification) ?: continue
        val sourceNeighbours = neighbours(from, sourceVector, verification)
        if (sourceNeighbours.isNotEmpty()) {
            val targetNeighbours = neighbours(to, targetVector, verification)
            compared++
            overlap += sourceNeighbours.intersect(targetNeighbours).size.toDouble() / sourceNeighbours.size
        }
    }
    return NeighbourAgreement(compared, if (compared == 0) null else overlap / compared)
}

/** The same point's vector on each side, or `null` when either side is not a dense vector. */
private suspend fun QdrantClient.vectorPair(
    record: Record,
    to: String,
    verification: MigrationVerification,
): Pair<List<Float>, List<Float>>? {
    val source = denseOf(record.vector, verification.using)
    val stored = retrieve(to, listOf(record.id), withVector = true).singleOrNull()?.vector
    val target = denseOf(stored, verification.using)
    return if (source != null && target != null) source to target else null
}

private suspend fun QdrantClient.neighbours(
    collection: String,
    vector: List<Float>,
    verification: MigrationVerification,
): Set<PointId> =
    search(collection) {
        query(vector)
        limit = verification.topK
        using = verification.using
    }.map { it.id }.toSet()

/**
 * Points [alias] at [collection] in one request, dropping whatever it pointed at before. Qdrant
 * applies the operations in a single alias change atomically, which is what makes this the one step a
 * reader crosses rather than a window where the alias resolves to nothing.
 */
private suspend fun QdrantClient.moveAlias(alias: String, collection: String) {
    val existed = listAliases().any { it.aliasName == alias }
    updateAliases {
        if (existed) deleteAlias(alias)
        createAlias(collection = collection, alias = alias)
    }
}

/** Reads a dense vector out of whatever shape the collection stores, or `null` if it is not dense. */
private fun denseOf(vector: VectorData?, using: String?): List<Float>? = when (vector) {
    null -> null
    is VectorData.Dense -> vector.values
    is VectorData.DenseArray -> vector.values.toList()
    is VectorData.Named -> denseOf(vector.vectors[using ?: return null], using = null)
    else -> null
}

private fun percent(value: Double): String = "${(value * 1000).toInt() / 10.0}%"

private fun MigrationReport.withAliasMoved(): MigrationReport = MigrationReport(
    source = source,
    target = target,
    copied = copied,
    resumed = resumed,
    sourceCount = sourceCount,
    targetCount = targetCount,
    sampledPairs = sampledPairs,
    recall = recall,
    aliasMoved = true,
)
