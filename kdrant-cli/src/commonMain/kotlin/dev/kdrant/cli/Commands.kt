package dev.kdrant.cli

import dev.kdrant.QdrantClient
import dev.kdrant.dsl.CreateCollectionBuilder
import dev.kdrant.dsl.VectorParamsBuilder
import dev.kdrant.migrate.MigrationVerification
import dev.kdrant.migrate.migrateCollection
import dev.kdrant.model.CollectionParams
import dev.kdrant.model.Distance
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import dev.kdrant.model.WithPayload
import kotlinx.coroutines.flow.Flow
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

    /**
     * A collection's snapshots, or one shard's with `--shard N`.
     *
     * The shard scope is the one somebody restoring a large deployment meets first, because a snapshot
     * of a sharded collection is taken and recovered per shard. It is a flag rather than a separate
     * command because every action means the same thing in both scopes.
     */
    suspend fun snapshot(client: QdrantClient, arguments: Arguments, files: Files, out: (String) -> Unit) {
        val shard = arguments.intOption("shard")
        val scope = shard?.let { " (shard $it)" } ?: ""
        when (val action = arguments.require(1, "an action: create, list, download, restore or delete")) {
            "create" -> {
                val collection = arguments.require(2, "a collection name")
                val snapshot = shard
                    ?.let { client.createShardSnapshot(collection, it) }
                    ?: client.createSnapshot(collection)
                out("${snapshot.name}\t${snapshot.size} bytes$scope")
            }

            "list" -> {
                val collection = arguments.require(2, "a collection name")
                val snapshots = shard
                    ?.let { client.listShardSnapshots(collection, it) }
                    ?: client.listSnapshots(collection)
                if (snapshots.isEmpty()) out("no snapshots$scope") else snapshots.forEach { out(it.name) }
            }

            "download" -> {
                val collection = arguments.require(2, "a collection name")
                val name = arguments.require(3, "a snapshot name")
                out(
                    download(files, arguments.option("out") ?: name) {
                        shard
                            ?.let { client.downloadShardSnapshot(collection, it, name) }
                            ?: client.downloadSnapshot(collection, name)
                    },
                )
            }

            "restore" -> {
                val collection = arguments.require(2, "a collection name")
                val location = arguments.require(3, "a snapshot location (an http(s):// or file:/// URL)")
                shard
                    ?.let { client.recoverShardSnapshot(collection, it, location) }
                    ?: client.recoverSnapshot(collection, location)
                out("restored $collection$scope from $location")
            }

            "delete" -> {
                val collection = arguments.require(2, "a collection name")
                val name = arguments.require(3, "a snapshot name")
                shard
                    ?.let { client.deleteShardSnapshot(collection, it, name) }
                    ?: client.deleteSnapshot(collection, name)
                out("deleted $name$scope")
            }

            else -> fail("unknown snapshot action '$action'; try create, list, download, restore or delete")
        }
    }

    /**
     * The three probes, which is the first thing anybody types at a node that is misbehaving and most of
     * the reason to have a binary at all.
     *
     * They mean different things and a single "healthy" would hide that: `livez` says the process is
     * running, `readyz` says it will accept traffic, and a node that is alive and not ready is the state
     * an operator is usually looking at. The exit code follows readiness, so `kdrant health && ...`
     * works in a script.
     */
    suspend fun health(client: QdrantClient, out: (String) -> Unit): Int {
        val live = runCatching { client.livez() }.getOrDefault(false)
        val ready = runCatching { client.readyz() }.getOrDefault(false)
        val healthy = runCatching { client.healthz() }.getOrDefault(false)

        out("livez\t${verdict(live)}")
        out("readyz\t${verdict(ready)}")
        out("healthz\t${verdict(healthy)}")
        if (live && !ready) out("alive but not ready: it is starting, recovering or waiting on consensus")
        return if (ready) 0 else 1
    }

    private fun verdict(value: Boolean): String = if (value) "ok" else "no"

    /**
     * Create, describe and delete. Deliberately not a query tool: Qdrant's dashboard is better at that
     * and is already running next to the server, and `kdrant search` would be the first step to a worse
     * copy of something that exists.
     */
    suspend fun collection(client: QdrantClient, arguments: Arguments, out: (String) -> Unit) {
        when (val action = arguments.require(1, "an action: create, describe or delete")) {
            "create" -> {
                val name = arguments.require(2, "a collection name")
                val size = arguments.intOption("size")
                    ?: fail("--size is required: a collection needs a vector size")
                require(size > 0) { "--size must be > 0" }
                val distance = distanceNamed(arguments.option("distance") ?: "cosine")
                client.createCollection(name) {
                    vector { this.size = size.toLong(); this.distance = distance }
                    arguments.intOption("shards")?.let { shardNumber = it }
                    arguments.intOption("replicas")?.let { replicationFactor = it }
                }
                out("created $name\t$size dims\t${distance.name.lowercase()}")
            }

            "describe" -> {
                val name = arguments.require(2, "a collection name")
                val info = client.getCollection(name)
                out("status\t${info.status.name.lowercase()}")
                out("points\t${info.pointsCount ?: "?"}")
                out("segments\t${info.segmentsCount ?: "?"}")
                val params = info.config?.params
                out("shards\t${params?.shardNumber ?: "?"}")
                out("replicas\t${params?.replicationFactor ?: "?"}")
                when (val vectors = params?.vectors) {
                    is VectorsConfig.Single ->
                        out("vector\t${vectors.params.size} dims\t${vectors.params.distance.name.lowercase()}")
                    is VectorsConfig.Named -> vectors.vectors.forEach { (vectorName, vp) ->
                        out("vector $vectorName\t${vp.size} dims\t${vp.distance.name.lowercase()}")
                    }
                    null -> out("vector\tnone declared")
                }
                info.payloadSchema.forEach { (field, schema) -> out("index $field\t${schema.dataType ?: "?"}") }
            }

            "delete" -> {
                val name = arguments.require(2, "a collection name")
                if (!arguments.flag("yes")) {
                    fail("deleting $name drops its points; pass --yes to confirm")
                }
                client.deleteCollection(name)
                out("deleted $name")
            }

            else -> fail("unknown collection action '$action'; try create, describe or delete")
        }
    }

    private fun distanceNamed(value: String): Distance = when (value.lowercase()) {
        "cosine" -> Distance.COSINE
        "dot" -> Distance.DOT
        "euclid", "euclidean" -> Distance.EUCLID
        "manhattan" -> Distance.MANHATTAN
        else -> fail("unknown distance '$value'; try cosine, dot, euclid or manhattan")
    }

    /**
     * Whole-storage snapshots, which is what somebody restoring a deployment reaches for rather than the
     * per-collection ones. Separate from [snapshot] rather than a flag on it, because a collection named
     * `storage` would otherwise decide which one you got.
     */
    suspend fun storageSnapshot(client: QdrantClient, arguments: Arguments, files: Files, out: (String) -> Unit) {
        when (val action = arguments.require(1, "an action: create, list, download or delete")) {
            "create" -> {
                val snapshot = client.createStorageSnapshot()
                out("${snapshot.name}\t${snapshot.size} bytes")
            }

            "list" -> {
                val snapshots = client.listStorageSnapshots()
                if (snapshots.isEmpty()) out("no snapshots") else snapshots.forEach { out(it.name) }
            }

            "download" -> {
                val name = arguments.require(2, "a snapshot name")
                out(download(files, arguments.option("out") ?: name) { client.downloadStorageSnapshot(name) })
            }

            "delete" -> {
                val name = arguments.require(2, "a snapshot name")
                client.deleteStorageSnapshot(name)
                out("deleted $name")
            }

            else -> fail("unknown storage-snapshot action '$action'; try create, list, download or delete")
        }
    }

    /** Streams a snapshot to [target] and reports what was written, shared by every snapshot scope. */
    private suspend fun download(files: Files, target: String, source: () -> Flow<ByteArray>): String {
        var written = 0L
        files.write(target) { sink ->
            source().collect { chunk ->
                sink(chunk)
                written += chunk.size
            }
        }
        return "wrote $written bytes to $target"
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
          kdrant health
          kdrant collections
          kdrant collection create <name> --size N [--distance D] [--shards N] [--replicas N]
          kdrant collection describe <name>
          kdrant collection delete <name> --yes
          kdrant scroll <collection> [--limit N]
          kdrant snapshot create <collection> [--shard N]
          kdrant snapshot list <collection> [--shard N]
          kdrant snapshot download <collection> <snapshot> [--shard N] [--out FILE]
          kdrant snapshot restore <collection> <location> [--shard N]
          kdrant snapshot delete <collection> <snapshot> [--shard N]
          kdrant storage-snapshot create|list|delete [<snapshot>]
          kdrant storage-snapshot download <snapshot> [--out FILE]
          kdrant migrate <from> <to> [--alias A] [--shards N] [--replicas N]
                                     [--batch N] [--recall R] [--checkpoint FILE]

        Connection:
          --host HOST        default localhost, or ${'$'}QDRANT_HOST
          --port PORT        default 6333, or ${'$'}QDRANT_PORT
          --api-key KEY      prefer ${'$'}QDRANT_API_KEY: a key on a command line is a key in the shell history
          --tls              use HTTPS
          --ca-file FILE     trust this PEM bundle instead of the system store

        health exits 0 when the node is ready and 1 otherwise, so it works in a script. A node that is
        alive and not ready is starting, recovering or waiting on consensus, and it says so.

        --shard scopes a snapshot action to one shard, which is how a snapshot of a sharded collection
        is taken and recovered. storage-snapshot is the whole node, which is what a full restore uses.

        migrate creates the target from the source's own vectors, so you do not restate a size and a
        distance you did not choose; --shards and --replicas override, which is what makes it a
        re-shard. It copies points as they are and cannot embed, so it moves what does not need new
        vectors: a re-shard, a config change, a copy between clusters. The alias moves only after the
        count and recall checks pass, and the checkpoint file makes an interrupted run resumable.
    """
}
