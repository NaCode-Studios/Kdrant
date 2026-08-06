package dev.kdrant.transport.rest

import dev.kdrant.testkit.QdrantClientContractSuite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer
import java.io.File

/**
 * The client contract, run against several Qdrant versions instead of one.
 *
 * A vector-database client is chosen on which servers it works with, because the server is already
 * running and the client is the part being added. Every question a chooser asks starts there — we are
 * on 1.17, can we use this — and until this existed the only evidence in the repository was a single
 * pinned image in a Testcontainers setup.
 *
 * ### Why this does not fail the build
 *
 * A cell that fails against an older server is the information this exists to publish. Failing the
 * build on it would mean the matrix quietly loses the versions that are interesting, until it reports
 * only green and has stopped saying anything. The blocking check is elsewhere: `qdrant-compat` in CI
 * runs the same contract against the pinned version and against `latest`, and that one is red when it
 * should be.
 *
 * What is asserted here is only that the run produced a result for every version, so a matrix that
 * silently ran nothing cannot pass.
 *
 * ### Writing the table
 *
 * With `KDRANT_UPDATE_COMPAT=1` the run rewrites the block between the compatibility markers in
 * `README.md`. That is the whole point of generating it: a table maintained by hand is a table that
 * describes the release before last.
 *
 * ```bash
 * KDRANT_UPDATE_COMPAT=1 ./gradlew :kdrant-transport-rest:jvmTest --tests '*QdrantVersionMatrix*'
 * ```
 */
class QdrantVersionMatrixIntegrationTest {

    private data class Result(val version: String, val passed: Int, val failed: List<String>)

    @TestFactory
    fun `the client contract, against every supported Qdrant version`(): List<DynamicTest> {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the Qdrant version matrix",
        )
        val results = mutableListOf<Result>()
        val tests = VERSIONS.map { version ->
            DynamicTest.dynamicTest("qdrant $version") {
                results += runContract(version)
            }
        }
        return tests + DynamicTest.dynamicTest("the matrix is published") {
            assertTrue(
                results.size == VERSIONS.size,
                "the matrix ran ${results.size} of ${VERSIONS.size} versions; a table with a hole in it " +
                    "is worse than no table",
            )
            val table = renderTable(results)
            println(table)
            // Also to a file, because Gradle does not show a test's stdout by default and a table that
            // exists only in a swallowed println is a table nobody can read off a CI run. The workflow
            // uploads this and puts it in the run summary.
            writeReport(table)
            if (System.getenv("KDRANT_UPDATE_COMPAT") == "1") writeIntoReadme(table)
        }
    }

    /** Runs every behaviour, counting rather than throwing: one failure must not hide the rest. */
    private fun runContract(version: String): Result {
        val container = QdrantContainer("qdrant/qdrant:$version").also { it.start() }
        return container.use {
            Kdrant(host = it.host, port = it.getMappedPort(REST_PORT)).use { client ->
                val suite = QdrantClientContractSuite(client, namePrefix = "matrix")
                val failed = mutableListOf<String>()
                suite.cases.forEach { (name, run) ->
                    runCatching { runBlocking { run() } }.onFailure { failed += name }
                }
                Result(version, suite.cases.size - failed.size, failed)
            }
        }
    }

    private fun renderTable(results: List<Result>): String = buildString {
        appendLine("| Qdrant | The shared client contract |")
        appendLine("| --- | --- |")
        results.forEach { result ->
            val verdict = if (result.failed.isEmpty()) {
                "**${result.passed}/${result.passed} pass**"
            } else {
                "${result.passed}/${result.passed + result.failed.size} pass — " +
                    result.failed.joinToString("; ") { "`$it` fails" }
            }
            appendLine("| `${result.version}` | $verdict |")
        }
    }

    /** Writes the table where a CI run can upload it and a person can read it. */
    private fun writeReport(table: String) {
        val target = File("build/reports/qdrant-matrix.md")
        target.parentFile.mkdirs()
        target.writeText(table)
        println("wrote the compatibility table to ${target.absolutePath}")
    }

    /**
     * Replaces the block between the markers, leaving the prose around it alone. A missing marker is a
     * failure rather than an append: silently adding a second table would be worse than not updating.
     */
    private fun writeIntoReadme(table: String) {
        val readme = File(System.getProperty("user.dir")).resolveSibling("README.md")
            .takeIf { it.exists() }
            ?: File("README.md").takeIf { it.exists() }
            ?: error("README.md not found from ${System.getProperty("user.dir")}")
        val text = readme.readText()
        val start = text.indexOf(START_MARKER)
        val end = text.indexOf(END_MARKER)
        check(start >= 0 && end > start) { "the compatibility markers are missing from ${readme.path}" }
        readme.writeText(
            text.substring(0, start + START_MARKER.length) + "\n" + table + text.substring(end),
        )
        println("wrote the compatibility table into ${readme.path}")
    }

    private companion object {
        const val REST_PORT = 6333
        const val START_MARKER = "<!-- qdrant-matrix:start -->"
        const val END_MARKER = "<!-- qdrant-matrix:end -->"

        /**
         * The four most recent minors, newest first. Overridable so a release can widen the window
         * without a code change.
         */
        val VERSIONS: List<String> = System.getenv("KDRANT_QDRANT_VERSIONS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("v1.19.0", "v1.18.3", "v1.17.1", "v1.16.3")
    }
}
