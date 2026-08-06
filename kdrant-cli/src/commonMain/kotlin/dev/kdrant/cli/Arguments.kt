package dev.kdrant.cli

import dev.kdrant.TrustAnchors

/**
 * The connection options every command shares, and the positional words left over for the command.
 *
 * Parsed by hand rather than with a library. A CLI that has to be downloaded as a single static binary
 * should not carry an argument parser's worth of dependency to read six flags, and the flags are the
 * part of this tool least likely to change.
 */
internal class Arguments private constructor(
    val host: String,
    val port: Int,
    val apiKey: String?,
    val useTls: Boolean,
    val caFile: String?,
    val positional: List<String>,
    private val options: Map<String, String>,
    private val flags: Set<String>,
) {

    /** The value of `--name`, or null. */
    fun option(name: String): String? = options[name]

    /** The value of `--name` as an Int, refusing text that is not one rather than defaulting silently. */
    fun intOption(name: String): Int? = options[name]?.let { value ->
        value.toIntOrNull() ?: fail("--$name takes a whole number, and was given '$value'")
    }

    /** The value of `--name` as a Double. */
    fun doubleOption(name: String): Double? = options[name]?.let { value ->
        value.toDoubleOrNull() ?: fail("--$name takes a number, and was given '$value'")
    }

    /** Whether `--name` was present. */
    fun flag(name: String): Boolean = name in flags

    /** The positional word at [index], or a failure naming what was expected. */
    fun require(index: Int, what: String): String =
        positional.getOrNull(index) ?: fail("expected $what")

    /** The trust decision the connection flags describe. */
    fun trustAnchors(readFile: (String) -> String): TrustAnchors =
        caFile?.let { TrustAnchors.Pem(readFile(it)) } ?: TrustAnchors.System

    companion object {

        /**
         * Reads the shared options out of [argv], leaving everything else positional.
         *
         * `--key value` and `--key=value` both work, because both are what people type. An unknown
         * `--flag` is kept rather than refused here: a subcommand may know it, and the subcommand is
         * what reports an option nobody knows.
         */
        fun parse(argv: List<String>, environment: (String) -> String?): Arguments {
            val positional = mutableListOf<String>()
            val options = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()

            var index = 0
            while (index < argv.size) {
                val argument = argv[index]
                when {
                    argument.startsWith("--") && argument.contains('=') -> {
                        val (name, value) = argument.removePrefix("--").split('=', limit = 2)
                        options[name] = value
                    }
                    argument.startsWith("--") -> {
                        val name = argument.removePrefix("--")
                        val next = argv.getOrNull(index + 1)
                        if (name in VALUELESS || next == null || next.startsWith("--")) {
                            flags += name
                        } else {
                            options[name] = next
                            index++
                        }
                    }
                    else -> positional += argument
                }
                index++
            }

            val port = options["port"]?.toIntOrNull()
                ?: environment("QDRANT_PORT")?.toIntOrNull()
                ?: DEFAULT_PORT
            return Arguments(
                host = options["host"] ?: environment("QDRANT_HOST") ?: "localhost",
                port = port,
                // The key comes from the environment by default. A key on a command line is a key in
                // the shell history and in every process listing on the machine.
                apiKey = options["api-key"] ?: environment("QDRANT_API_KEY"),
                useTls = "tls" in flags || options["tls"] == "true",
                caFile = options["ca-file"],
                positional = positional,
                options = options,
                flags = flags,
            )
        }

        private const val DEFAULT_PORT = 6333

        /** Options that never take a value, so the word after them is positional. */
        private val VALUELESS = setOf("tls", "help", "version", "wait", "force")
    }
}

/** A message for the user rather than a stack trace: this is a terminal, not a service. */
internal class CliFailure(message: String) : RuntimeException(message)

internal fun fail(message: String): Nothing = throw CliFailure(message)
