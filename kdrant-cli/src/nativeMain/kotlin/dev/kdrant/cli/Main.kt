package dev.kdrant.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.exit
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.printf
import platform.posix.remove
import platform.posix.stderr

/**
 * One static binary, no JVM, no classpath, no install step.
 *
 * The entry point does the three things a process entry point should: hand the outside world to the
 * code that decides, run it, and exit with what it decided.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val code = runBlocking {
        run(
            argv = args.toList(),
            files = PosixFiles,
            environment = { name -> getenv(name)?.toKString() },
            out = { line -> printf("%s\n", line) },
            err = { line -> fprintf(stderr, "%s\n", line) },
        )
    }
    exit(code)
}

/** The filesystem, through the C library, which is what a Kotlin/Native binary has. */
@OptIn(ExperimentalForeignApi::class)
private object PosixFiles : Files {

    /**
     * Reads in chunks until EOF rather than asking for the size first.
     *
     * `fseek` and `ftell` name an offset type that is 64-bit on Linux and macOS and 32-bit on Windows,
     * and a source set shared by all three cannot name it. Reading until the stream ends needs no
     * offset at all, and the files this tool reads are a checkpoint line and a CA bundle.
     */
    override fun read(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        try {
            val chunk = ByteArray(READ_CHUNK)
            var text = StringBuilder()
            while (true) {
                val read: Int = chunk.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), READ_CHUNK.convert(), file).convert()
                }
                if (read <= 0) break
                text = text.append(chunk.decodeToString(0, read))
            }
            return text.toString()
        } finally {
            fclose(file)
        }
    }

    override fun writeText(path: String, text: String) {
        val file = fopen(path, "wb") ?: fail("could not write $path")
        try {
            val bytes = text.encodeToByteArray()
            writeAll(file, bytes, bytes.size, path)
        } finally {
            fclose(file)
        }
    }

    override fun delete(path: String) {
        remove(path)
    }

    override suspend fun write(path: String, body: suspend ((ByteArray) -> Unit) -> Unit) {
        val file = fopen(path, "wb") ?: fail("could not write $path")
        try {
            body { chunk -> writeAll(file, chunk, chunk.size, path) }
        } finally {
            fclose(file)
        }
    }

    /**
     * `fwrite` may write fewer items than asked for, and treating a short write as success is how a
     * snapshot ends up truncated on a full disk with a zero exit code.
     */
    private fun writeAll(
        file: kotlinx.cinterop.CPointer<platform.posix.FILE>,
        bytes: ByteArray,
        size: Int,
        path: String,
    ) {
        if (size == 0) return
        var written = 0
        while (written < size) {
            val count: Int = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(written), 1.convert(), (size - written).convert(), file).convert()
            }
            if (count <= 0) fail("could not write all of $path (disk full?)")
            written += count
        }
    }
}

/** Enough to read a checkpoint line or a CA bundle in one or two passes. */
private const val READ_CHUNK = 8 * 1024
