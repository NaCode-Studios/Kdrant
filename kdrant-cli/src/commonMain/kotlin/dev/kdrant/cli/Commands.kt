package dev.kdrant.cli

import dev.kdrant.QdrantClient
import dev.kdrant.migrate.MigrationVerification
import dev.kdrant.migrate.migrateCollection
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

        out("copying $from -> $to (batch $batch, recall >= $recall, checkpoint $checkpointPath)")
        val report = client.migrateCollection(
            from = from,
            to = to,
            alias = alias,
            batchSize = batch,
            checkpoints = FileCheckpointStore(files, checkpointPath),
            verification = MigrationVerification(minRecall = recall),
        )
        out("copied ${report.copied} point(s); source ${report.sourceCount}, target ${report.targetCount}")
        out("recall ${report.recall}")
        out(if (alias == null) "no alias was moved (pass --alias to move one)" else "alias '$alias' now points at $to")
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
          kdrant migrate <from> <to> [--alias A] [--batch N] [--recall R] [--checkpoint FILE]

        Connection:
          --host HOST        default localhost, or ${'$'}QDRANT_HOST
          --port PORT        default 6333, or ${'$'}QDRANT_PORT
          --api-key KEY      prefer ${'$'}QDRANT_API_KEY: a key on a command line is a key in the shell history
          --tls              use HTTPS
          --ca-file FILE     trust this PEM bundle instead of the system store

        migrate copies points as they are. It cannot embed, so it moves what does not need new
        vectors: a re-shard, a config change, a copy between clusters. The alias moves only after the
        count and recall checks pass, and the checkpoint file makes an interrupted run resumable.
    """
}
