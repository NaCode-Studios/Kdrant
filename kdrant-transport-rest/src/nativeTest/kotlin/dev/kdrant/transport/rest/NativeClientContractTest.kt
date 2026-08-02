package dev.kdrant.transport.rest

import dev.kdrant.testkit.QdrantClientContractSuite
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.fail

/**
 * The client contract, run from a native binary against a real Qdrant.
 *
 * This is the test that makes the multiplatform claim mean something. `kdrant-core` compiled for iOS
 * and Linux for two releases before this one, and none of those targets could send a request; a build
 * that only proves the klib links proves exactly that. So the same behaviours the JVM asserts are run
 * here, from a binary with no JVM anywhere near it.
 *
 * Testcontainers is JVM-only, so the Qdrant has to be up already. CI starts one and points
 * `KDRANT_QDRANT_HOST` and `KDRANT_QDRANT_PORT` at it. Without them the case is skipped rather than
 * failed, so `./gradlew build` on a laptop stays green.
 *
 * A Kotlin/Native test binary cannot register tests at runtime, so this is one test walking
 * [QdrantClientContractSuite.cases] and reporting every case that failed rather than only the first.
 */
class NativeClientContractTest {

    @Test
    fun theClientContractPassesFromANativeBinary() {
        val host = env("KDRANT_QDRANT_HOST")
        if (host == null) {
            // A job that was supposed to run this and silently skipped it would report green for
            // having proven nothing, which is the failure mode the whole test exists to close.
            if (env("KDRANT_QDRANT_REQUIRED") != null) {
                fail("KDRANT_QDRANT_REQUIRED is set and KDRANT_QDRANT_HOST did not reach the test binary")
            }
            println("KDRANT_QDRANT_HOST is not set; skipping the native client contract")
            return
        }
        val port = env("KDRANT_QDRANT_PORT")?.toIntOrNull() ?: 6333

        val failures = mutableListOf<String>()
        Kdrant(host = host, port = port).use { client ->
            val suite = QdrantClientContractSuite(client, namePrefix = "native-contract")
            for ((name, run) in suite.cases) {
                val error = runBlocking { runCatching { run() }.exceptionOrNull() }
                if (error != null) failures += "$name\n    ${error::class.simpleName}: ${error.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of the contract's cases failed from a native binary:\n" +
                    failures.joinToString("\n") { "  $it" },
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
}
