package dev.kdrant.cli

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.VectorParamsBuilder
import dev.kdrant.migrate.MigrationVerification
import dev.kdrant.migrate.migrateCollection
import dev.kdrant.model.CollectionParams
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * What the tool does, one function per subcommand.
 *
 * The scope is the operations that are **not** requests: the things somebody does with a terminal open
 * next to a Qdrant that is misbehaving. Querying is not among them — Qdrant's own dashboard is better
 * at it and is already running next to the server.
 */
internal object Commands {

    suspend fun collections(client: QdrantClient, out: (String) -> Unit) {
        val collections = client.listCollections()
        if (collections.isEmpty()) {
            out("no collections")
            return
        }
        collections.forEach { collection ->
            val info = runCatching { client.getCollection(collection.name) }.getOrNull()
            val points = info?.pointsCount?.toString() ?: "?"
            val status = info?.status?.name?.lowercase() ?: "unknown"
            out("${collection.name}\t$points points\t$status")
        }
    }

    suspend fun scroll(client: QdrantClient, arguments: Arguments, out: (String) -> Unit) {
        val collection = arguments.require(1, "a collection name: kdrant scroll <collection>")
        val limit = arguments.intOption("limit") ?: DEFAULT_SCROLL_LIMIT
        require(limit > 0) { "--limit must be > 0" }

        val records = client.scroll(collection, pageSize = minOf(limit, DEFAULT_PAGE_SIZE)) {
            withPayload = WithPayload.All
        }.take(limit).toList()

        records.forEach { record -> out("${record.id}\t${record.payload ?: "{}"}") }
        out("— ${records.size} point(s)")
    }

    suspend fun snapshot(client: QdrantClient, arguments: Arguments, files: Files, out: (String) -> Unit) {
        when (val action = arguments.require(1, "an action: create, list, download, restore or delete")) {
            "create" -> {
                val collection = arguments.require(2, "a collection name")
                val snapshot = client.createSnapshot(collection)
                out("${snapshot.name}\t${snapshot.size} bytes")
            }

            "list" -> {
                val collection = arguments.require(2, "a collection name")
                val snapshots = client.listSnapshots(collection)
                if (snapshots.isEmpty()) out("no snapshots") else snapshots.forEach { out(it.name) }
            }

            "download" -> {
                val collection = arguments.require(2, "a collection name")
                val name = arguments.require(3, "a snapshot name")
                val target = arguments.option("out") ?: name
                var written = 0L
                files.write(target) { sink ->
                    client.downloadSnapshot(collection, name).collect { chunk ->
                        sink(chunk)
                        written += chunk.size
                    }
                }
                out("wrote $written bytes to $target")
            }

            "restore" -> {
                val collection = arguments.require(2, "a collection name")
                val location = arguments.require(3, "a snapshot location (an http(s):// or file:/// URL)")
                client.recoverSnapshot(collection, location)
                out("restored $collection from $location")
            }

            "delete" -> {
                val collection = arguments.require(2, "a collection name")
                val name = arguments.require(3, "a snapshot name")
                client.deleteSnapshot(collection, name)
                out("deleted $name")
            }

            else -> fail("unknown snapshot action '$action'; try create, list, download, restore or delete")
        }
    }

    /**
     * M42's procedure, with the checkpoint on disk and the recall threshold on the command line.
     *
     * **The target is created from the source's own configuration.** A migration that does not
     * re-embed keeps the same vectors, so asking the person at the terminal to restate a vector size
     * and a distance they did not choose is asking them to get it wrong. `--shards` and `--replicas`
     * override, which is what makes `kdrant migrate a b --shards 4` a re-shard rather than a copy.
     *
     * **It cannot re-embed.** A CLI has no model, so it migrates what does not need new vectors: a
     * re-shard, a config change, a copy between clusters. Saying that plainly is better than a tool
     * that looks like it can move a collection onto a new embedding model and quietly copies the old
     * vectors into it.
     */
    suspend fun migrate(client: QdrantClient, arguments: Arguments, files: Files, out: (String) -> Unit) {
        val from = arguments.require(1, "a source collection: kdrant migrate <from> <to>")
        val to = arguments.require(2, "a target collection: kdrant migrate <from> <to>")
        val alias = arguments.option("alias")
        val recall = arguments.doubleOption("recall") ?: DEFAULT_RECALL
        val batch = arguments.intOption("batch") ?: DEFAULT_MIGRATE_BATCH
        val checkpointPath = arguments.option("checkpoint") ?: "kdrant-migrate-$from-to-$to.checkpoint"

        val source = client.getCollection(from).config?.params
            ?: fail("$from reports no configuration, so there is nothing to create $to from")
        val shards = arguments.intOption("shards") ?: source.shardNumber
        val replicas = arguments.intOption("replicas") ?: source.replicationFactor

        out("copying $from -> $to (batch $batch, recall >= $recall, checkpoint $checkpointPath)")
        out("target: ${describe(source)}, ${shards ?: 1} shard(s), ${replicas ?: 1} replica(s)")

        val report = client.migrateCollection(
            from = from,
            to = to,
            alias = alias,
            batchSize = batch,
            checkpoints = FileCheckpointStore(files, checkpointPath),
            verification = MigrationVerification(minRecall = recall),
            createTarget = { create(targetConfigurationOf(source, shards, replicas)) },
        )
        out("copied ${report.copied} point(s); source ${report.sourceCount}, target ${report.targetCount}")
        out("recall ${report.recall}")
        out(if (alias == null) "no alias was moved (pass --alias to move one)" else "alias '$alias' now points at $to")
    }

    /**
     * What the target should be created as: the source's vectors, with the sharding the caller asked
     * for. A function of its inputs rather than a lambda that writes into a builder, so the decision
     * can be asserted without a Qdrant to write it to.
     *
     * Only the vectors and the sharding, deliberately. HNSW tuning, quantization and optimizer
     * settings are things an operator changed on the source for a reason, and carrying them onto a
     * collection that is about to be re-sharded would carry a decision made for a different layout.
     * They stay the server's defaults on the target and are set afterwards if they are wanted.
     */
    internal fun targetConfigurationOf(
        source: CollectionParams,
        shards: Int?,
        replicas: Int?,
    ): CollectionParams = CollectionParams(
        vectors = source.vectors,
        sparseVectors = source.sparseVectors,
        shardNumber = shards ?: source.shardNumber,
        replicationFactor = replicas ?: source.replicationFactor,
        onDiskPayload = source.onDiskPayload,
    )

    /** Puts [target] into the collection DSL. Mechanical, and the only part that needs a builder. */
    private fun CreateCollectionBuilder.create(target: CollectionParams) {
        when (val vectors = target.vectors) {
            is VectorsConfig.Single -> vector { copyFrom(vectors.params) }
            is VectorsConfig.Named -> vectors.vectors.forEach { (name, params) ->
                namedVector(name) { copyFrom(params) }
            }
            null -> Unit
        }
        target.sparseVectors?.forEach { (name, params) -> sparseVector(name) { modifier = params.modifier } }
        target.onDiskPayload?.let { onDiskPayload = it }
        target.shardNumber?.let { shardNumber = it }
        target.replicationFactor?.let { replicationFactor = it }
    }

    private fun VectorParamsBuilder.copyFrom(params: VectorParams) {
        size = params.size
        distance = params.distance
        params.onDisk?.let { onDisk = it }
        params.datatype?.let { datatype = it }
        params.multivectorConfig?.let { multivector = it.comparator }
    }

    /** What the target is about to be created as, printed before anything is copied. */
    private fun describe(source: CollectionParams): String = when (val vectors = source.vectors) {
        is VectorsConfig.Single -> "one ${vectors.params.size}-dimension vector, ${vectors.params.distance}"
        is VectorsConfig.Named -> vectors.vectors.entries.joinToString(", ") { (name, params) ->
            "$name ${params.size}d ${params.distance}"
        }
        null -> "no dense vectors"
    }

    fun help(out: (String) -> Unit) {
        USAGE.trimIndent().lines().forEach(out)
    }

    private const val DEFAULT_SCROLL_LIMIT = 20
    private const val DEFAULT_PAGE_SIZE = 64
    private const val DEFAULT_MIGRATE_BATCH = 256
    private const val DEFAULT_RECALL = 0.99

    private val USAGE = """
        kdrant — the Qdrant operations that are not requests

        Usage:
          kdrant collections
          kdrant scroll <collection> [--limit N]
          kdrant snapshot create <collection>
          kdrant snapshot list <collection>
          kdrant snapshot download <collection> <snapshot> [--out FILE]
          kdrant snapshot restore <collection> <location>
          kdrant snapshot delete <collection> <snapshot>
          kdrant migrate <from> <to> [--alias A] [--shards N] [--replicas N]
                                     [--batch N] [--recall R] [--checkpoint FILE]

        Connection:
          --host HOST        default localhost, or ${'$'}QDRANT_HOST
          --port PORT        default 6333, or ${'$'}QDRANT_PORT
          --api-key KEY      prefer ${'$'}QDRANT_API_KEY: a key on a command line is a key in the shell history
          --tls              use HTTPS
          --ca-file FILE     trust this PEM bundle instead of the system store

        migrate creates the target from the source's own vectors, so you do not restate a size and a
        distance you did not choose; --shards and --replicas override, which is what makes it a
        re-shard. It copies points as they are and cannot embed, so it moves what does not need new
        vectors: a re-shard, a config change, a copy between clusters. The alias moves only after the
        count and recall checks pass, and the checkpoint file makes an interrupted run resumable.
    """
}
