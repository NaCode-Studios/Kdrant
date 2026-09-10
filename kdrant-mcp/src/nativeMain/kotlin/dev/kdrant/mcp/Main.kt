@file:OptIn(ExperimentalForeignApi::class)

package dev.kdrant.mcp

import dev.kdrant.transport.rest.Kdrant
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.getenv
import platform.posix.stderr

/**
 * `kdrant-mcp`, an MCP server for Qdrant that is a binary rather than an interpreter.
 *
 * An agent spawns and kills this repeatedly, so cold start and install footprint are most of what
 * distinguishes it from the Python server that is the only other option. That is the whole argument for
 * the module, and it is why the transport below is hand-written: the MCP SDK's stdio transport takes a
 * kotlinx-io `Source` and `Sink`, and kotlinx-io ships those for the JVM and not for Native.
 *
 * Usage: `kdrant-mcp [--host H] [--port P] [--allow-writes]`, or `QDRANT_HOST`, `QDRANT_PORT` and
 * `QDRANT_API_KEY`. Nothing is written to stdout except JSON-RPC: it is the protocol's channel, and a
 * stray println is a client that disconnects with a parse error.
 */
public fun main(args: Array<String>) {
    val arguments = args.toList()
    val host = arguments.option("--host") ?: env("QDRANT_HOST") ?: "localhost"
    val port = (arguments.option("--port") ?: env("QDRANT_PORT"))?.toIntOrNull() ?: 6333
    val allowWrites = "--allow-writes" in arguments

    keepStdoutForTheProtocol()

    val client = Kdrant(host = host, port = port) { apiKey = env("QDRANT_API_KEY") }
    runBlocking {
        client.use { qdrant ->
            val transport = StdioServerTransport(
                input = FileDescriptorSource(STANDARD_INPUT).buffered(),
                output = FileDescriptorSink(STANDARD_OUTPUT).buffered(),
            )
            val session = mcpServer(qdrant, allowWrites).createSession(transport)
            // Stay up until the client closes the pipe. An agent kills this process; it does not ask it
            // to stop, so the close callback is the only end this has.
            val finished = CompletableDeferred<Unit>()
            session.onClose { finished.complete(Unit) }
            finished.await()
        }
    }
}

/**
 * Stdout is the protocol's channel and nothing else may write to it.
 *
 * The MCP SDK logs through kotlin-logging, whose default on every target here prints to stdout, starting
 * with a `kotlin-logging: initializing...` banner before the first JSON-RPC frame. A client reading that
 * gets a parse error on the handshake and disconnects, which is a server that works in a terminal and is
 * broken everywhere it is actually used.
 *
 * So the banner is off and the appender is stderr, which is also where the MCP convention puts a server's
 * own diagnostics. Silencing the logging entirely would have been shorter and would have left an agent
 * with nothing to read when this misbehaves.
 */
private fun keepStdoutForTheProtocol() {
    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.appender = StandardErrorAppender
}

private object StandardErrorAppender : FormattingAppender() {
    override fun logFormattedMessage(loggingEvent: KLoggingEvent, formattedMessage: Any?) {
        fprintf(stderr, "%s\n", formattedMessage?.toString() ?: "")
        fflush(stderr)
    }
}

private fun List<String>.option(name: String): String? {
    val index = indexOf(name)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

private fun env(name: String): String? = getenv(name)?.toKString()
