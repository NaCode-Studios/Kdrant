package dev.kdrant.cli

import dev.kdrant.KdrantException
import dev.kdrant.QdrantClient
import dev.kdrant.transport.rest.Kdrant

/**
 * The command line, with everything that touches the outside world passed in.
 *
 * `main` is three lines in `nativeMain`; this is the part with the decisions in it, and it takes its
 * filesystem, its environment and its output as parameters so those decisions can be tested without a
 * process, a Qdrant or a terminal.
 *
 * @return the process exit code. `0` for success, `1` for a failure the user can act on, `2` for being
 *   asked to do something that is not a command.
 */
internal suspend fun run(
    argv: List<String>,
    files: Files,
    environment: (String) -> String?,
    out: (String) -> Unit,
    err: (String) -> Unit,
    connect: (Arguments) -> QdrantClient = { openClient(it, files) },
): Int {
    val arguments = Arguments.parse(argv, environment)
    val command = arguments.positional.firstOrNull()

    if (command == null || arguments.flag("help") || command == "help") {
        Commands.help(out)
        return if (command == null) USAGE_ERROR else 0
    }

    return try {
        connect(arguments).use { client ->
            when (command) {
                "collections" -> Commands.collections(client, out)
                "scroll" -> Commands.scroll(client, arguments, out)
                "snapshot" -> Commands.snapshot(client, arguments, files, out)
                "migrate" -> Commands.migrate(client, arguments, files, out)
                else -> {
                    err("unknown command '$command'")
                    Commands.help(err)
                    return USAGE_ERROR
                }
            }
        }
        0
    } catch (e: CliFailure) {
        err("kdrant: ${e.message}")
        USAGE_ERROR
    } catch (e: KdrantException) {
        // The failure a caller acts on, phrased for someone at a terminal rather than for a log
        // aggregator. Retryable is worth saying out loud here: it is the difference between waiting
        // and paging somebody.
        err("kdrant: ${e.message}")
        if (e.retryable) err("this one is worth retrying; the cluster is in a state that clears.")
        FAILURE
    } catch (e: IllegalArgumentException) {
        err("kdrant: ${e.message}")
        USAGE_ERROR
    } catch (e: IllegalStateException) {
        err("kdrant: ${e.message}")
        FAILURE
    }
}

/** Opens the client the connection flags describe. Separated so [run] can be given a fake one. */
internal fun openClient(arguments: Arguments, files: Files): QdrantClient = Kdrant(
    host = arguments.host,
    port = arguments.port,
) {
    apiKey = arguments.apiKey
    useTls = arguments.useTls || arguments.caFile != null
    trustAnchors = arguments.trustAnchors { path ->
        files.read(path) ?: fail("could not read the CA bundle at $path")
    }
}

private const val FAILURE = 1
private const val USAGE_ERROR = 2
