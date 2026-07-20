@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.kdrantConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Client-layer validation (M19) — these reject before any request reaches the (never-called) engine. */
class ClientValidationM19Test {

    private fun client() = QdrantClient(
        RestQdrantTransport(kdrantConfig("h", 6333) {}, MockEngine { respond("{}", HttpStatusCode.OK) }),
    )

    @Test
    fun `updateAliases with no action is rejected`() {
        client().use { c ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { c.updateAliases { } } }
        }
    }

    @Test
    fun `facet rejects a non-positive limit`() {
        client().use { c ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { c.facet("docs", "lang", limit = 0) } }
        }
    }
}
