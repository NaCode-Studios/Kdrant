package dev.kdrant

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KdrantConfigTest {

    @Test
    fun `rejects an out-of-range port`() {
        assertThrows(IllegalArgumentException::class.java) {
            KdrantConfig(host = "h", port = 0)
        }
    }

    @Test
    fun `builds a valid config`() {
        assertDoesNotThrow {
            kdrantConfig("h", 6333) { apiKey = "secret"; useTls = true }
        }
    }

    @Test
    fun `toString redacts the api key`() {
        val config = kdrantConfig("h", 6333) { apiKey = "super-secret" }
        org.junit.jupiter.api.Assertions.assertFalse(config.toString().contains("super-secret"))
    }
}
