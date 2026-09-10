package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.model.QuotaConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.qdrant.QdrantContainer

/**
 * M62 against a real Qdrant. The quota is cluster state rather than a request shape, so a mock proves
 * only that the client can spell the call: whether the server keeps what it was given, and reports
 * utilization it actually measured, is a question a running node has to answer.
 *
 * REST only, and deliberately so. Qdrant serves quotas over HTTP alone, and the gRPC engine refuses
 * them by name, which `RestOnlyOperationsTest` asserts on its side.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotaIntegrationTest {

    private lateinit var container: QdrantContainer
    private lateinit var client: QdrantClient

    @BeforeAll
    fun startQdrant() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker not available; skipping the quota integration test",
        )
        container = QdrantContainer(IMAGE).also { it.start() }
        client = Kdrant(host = container.host, port = container.getMappedPort(6333)) {}
    }

    @AfterAll
    fun stopQdrant() {
        if (::client.isInitialized) client.close()
        if (::container.isInitialized && container.isRunning) container.close()
    }

    @Test
    fun `a fresh node reports a quota that is off and a utilization it measured`() = runBlocking {
        val status = client.quotas()

        assertEquals(false, status.config.enabled ?: false, "a node should not start with quotas enforced")
        // The utilization is read from the OS rather than configured, so the assertion is that it is
        // there and plausible. A hard number would be asserting something about the runner.
        val memory = status.usage.residentMemoryPercent
        assertNotNull(memory, "no resident memory reported")
        assertTrue(memory!! in 0..100, "resident memory percent out of range: $memory")
    }

    @Test
    fun `a quota survives the round trip and unsetting a limit removes it`() = runBlocking {
        val applied = client.updateQuotas(
            QuotaConfig(
                enabled = true,
                maxResidentMemoryPercent = 95,
                maxDiskUsagePercent = 99,
                releaseMarginPercent = 5,
            ),
        )

        assertEquals(true, applied.config.enabled)
        assertEquals(95, applied.config.maxResidentMemoryPercent)
        assertEquals(99, applied.config.maxDiskUsagePercent)
        assertEquals(5, applied.config.releaseMarginPercent)
        assertEquals(applied.config, client.quotas().config, "reading it back gave a different config")

        // The update replaces rather than merges, which is the half a caller gets wrong: sending a
        // config that names one limit silently drops the others.
        val replaced = client.updateQuotas(QuotaConfig(enabled = true, maxDiskUsagePercent = 90))

        assertEquals(90, replaced.config.maxDiskUsagePercent)
        assertEquals(null, replaced.config.maxResidentMemoryPercent, "the memory limit outlived the config that set it")

        client.updateQuotas(QuotaConfig(enabled = false))
    }

    private companion object {
        val IMAGE: String = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.19.1"
    }
}
