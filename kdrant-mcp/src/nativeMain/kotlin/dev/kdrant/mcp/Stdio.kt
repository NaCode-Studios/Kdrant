package dev.kdrant.mcp

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * Stdin and stdout as kotlinx-io, because the MCP SDK's stdio transport wants a `Source` and a `Sink` and
 * kotlinx-io ships those for the JVM and not for Native.
 *
 * The two primitives below are `expect` rather than a `platform.posix.read` call in this file, and the
 * reason is narrow: `read` and `write` take a `size_t`, which is 64-bit on Linux and macOS and 32-bit on
 * mingw, and the compiler refuses a declaration in a shared native source set whose signature varies by
 * platform. So the calls live one level down, where each resolves to exactly one declaration, and
 * everything that is actually logic stays here once.
 */

/** Reads into [into], returning the bytes read, 0 at end of input, or negative on error. */
internal expect fun readDescriptor(descriptor: Int, into: ByteArray, count: Int): Int

/** Writes [count] bytes from [from] at [offset], returning the bytes written or negative on error. */
internal expect fun writeDescriptor(descriptor: Int, from: ByteArray, offset: Int, count: Int): Int

/**
 * A kotlinx-io source over a file descriptor.
 *
 * `read` returning 0 is end of input and has to become -1 here, because that is what a `RawSource`
 * reports when it is exhausted; returning 0 instead would spin the reader.
 */
internal class FileDescriptorSource(private val descriptor: Int) : RawSource {
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "the source is closed" }
        require(byteCount >= 0) { "byteCount cannot be negative, was $byteCount" }
        if (byteCount == 0L) return 0
        val wanted = minOf(byteCount, BUFFER.toLong()).toInt()
        val chunk = ByteArray(wanted)
        val read = readDescriptor(descriptor, chunk, wanted)
        if (read <= 0) return -1
        sink.write(chunk, 0, read)
        return read.toLong()
    }

    override fun close() {
        closed = true
    }
}

/** A kotlinx-io sink over a file descriptor, writing until the whole buffer is out. */
internal class FileDescriptorSink(private val descriptor: Int) : RawSink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "the sink is closed" }
        require(byteCount >= 0) { "byteCount cannot be negative, was $byteCount" }
        var remaining = byteCount
        while (remaining > 0) {
            val chunk = source.readByteArray(minOf(remaining, BUFFER.toLong()).toInt())
            var offset = 0
            // A short write is normal on a pipe, so loop rather than assume the whole chunk went.
            while (offset < chunk.size) {
                val written = writeDescriptor(descriptor, chunk, offset, chunk.size - offset)
                check(written > 0) { "could not write to descriptor $descriptor" }
                offset += written
            }
            remaining -= chunk.size
        }
    }

    override fun flush(): Unit = Unit

    override fun close() {
        closed = true
    }
}

// 0 and 1 by literal rather than through STDIN_FILENO and STDOUT_FILENO, which are POSIX constants mingw
// does not carry. The numbers are the same everywhere this compiles.
internal const val STANDARD_INPUT: Int = 0
internal const val STANDARD_OUTPUT: Int = 1
private const val BUFFER = 8192
