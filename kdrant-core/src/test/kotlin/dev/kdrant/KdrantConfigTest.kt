package dev.kdrant

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        val config = kdrantConfig("h", 6333) { apiKey = "super-secret"; useTls = true }
        org.junit.jupiter.api.Assertions.assertFalse(config.toString().contains("super-secret"))
    }

    @Test
    fun `rejects an api key without TLS`() {
        assertThrows(IllegalArgumentException::class.java) {
            kdrantConfig("h", 6333) { apiKey = "secret" } // useTls defaults to false
        }
    }

    @Test
    fun `rejects a negative maxRetries`() {
        assertThrows(IllegalArgumentException::class.java) {
            kdrantConfig("h", 6333) { maxRetries = -1 }
        }
    }

    @Test
    fun `rejects a non-positive connect or socket timeout`() {
        assertThrows(IllegalArgumentException::class.java) {
            kdrantConfig("h", 6333) { connectTimeout = Duration.ZERO }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kdrantConfig("h", 6333) { socketTimeout = -(1.seconds) }
        }
    }

    @Test
    fun `accepts and stores connect and socket timeouts`() {
        val config = kdrantConfig("h", 6333) {
            connectTimeout = 2.seconds
            socketTimeout = 10.seconds
        }
        assertEquals(2.seconds, config.connectTimeout)
        assertEquals(10.seconds, config.socketTimeout)
    }
}
