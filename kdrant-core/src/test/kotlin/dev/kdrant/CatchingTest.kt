package dev.kdrant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatchingTest {

    @Test
    fun `wraps a successful result`() = runBlocking {
        val result = catching { 42 }
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `wraps a thrown exception as a failure`() = runBlocking {
        val result = catching<Int> { throw KdrantException.InvalidRequest("bad request") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is KdrantException.InvalidRequest)
    }

    @Test
    fun `re-throws CancellationException instead of trapping it`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { catching<Int> { throw CancellationException("cancelled") } }
        }
    }
}
