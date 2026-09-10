package dev.kdrant.cli

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The help text and the dispatcher are two lists of commands that have to stay the same list.
 *
 * They drift in the direction that is hard to notice: a command gets added, works, is tested against a
 * real Qdrant in CI, and is documented nowhere, so the only people who find it are the ones reading the
 * source. This is the cheap half of the check. The expensive half is `prove-cli.sh`, which runs every
 * one of these against a real node on every push.
 */
class HelpAndDispatchTest {

    private val help: String = buildString {
        Commands.help { line -> appendLine(line) }
    }

    @Test
    fun `every command the dispatcher accepts is in the help text`() {
        val commands = listOf(
            "health",
            "collections",
            "collection create",
            "collection describe",
            "collection delete",
            "scroll",
            "snapshot create",
            "snapshot list",
            "snapshot download",
            "snapshot restore",
            "snapshot delete",
            "storage-snapshot",
            "migrate",
        )

        commands.forEach { command ->
            assertTrue("kdrant $command" in help, "the help text does not mention '$command'")
        }
    }

    @Test
    fun `the flags the commands read are documented`() {
        listOf("--shard", "--size", "--distance", "--yes", "--limit", "--checkpoint", "--out")
            .forEach { flag -> assertTrue(flag in help, "the help text does not mention '$flag'") }
    }

    @Test
    fun `the help says what health's exit code means because a script depends on it`() {
        assertTrue("exits 0" in help, help)
    }
}
