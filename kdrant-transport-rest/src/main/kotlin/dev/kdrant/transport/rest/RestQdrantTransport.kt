@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfig
import dev.kdrant.KdrantException
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.PointStruct
import dev.kdrant.transport.QdrantTransport
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder

/**
 * REST/Ktor engine: the default Kdrant transport.
 *
 * Owns a Ktor [HttpClient] (CIO, coroutine-native) preconfigured with the base URL, `api-key`
 * auth, JSON content negotiation, and a request timeout. Translates HTTP and transport failures
 * into a [KdrantException] and always re-throws [CancellationException].
 *
 * @param engine optional engine override, used by tests to plug in a `MockEngine`; production
 *   code leaves it null and gets the CIO engine.
 * @param upsertBatchSize maximum points per upsert request; larger batches are split to stay
 *   under Qdrant's 32 MiB REST payload cap.
 */
internal class RestQdrantTransport(
    private val config: KdrantConfig,
    engine: HttpClientEngine? = null,
    private val upsertBatchSize: Int = 1000,
) : QdrantTransport {

    init {
        require(upsertBatchSize > 0) { "upsertBatchSize must be > 0, was $upsertBatchSize" }
    }

    private val client: HttpClient =
        if (engine != null) {
            HttpClient(engine) { applyCommonConfig() }
        } else {
            HttpClient(CIO) { applyCommonConfig() }
        }

    private fun HttpClientConfig<*>.applyCommonConfig() {
        expectSuccess = false
        install(ContentNegotiation) { json(KdrantJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        defaultRequest {
            url {
                protocol = if (config.useTls) URLProtocol.HTTPS else URLProtocol.HTTP
                host = config.host
                port = config.port
            }
            config.apiKey?.let { headers.append("api-key", it) }
            contentType(ContentType.Application.Json)
        }
    }

    override suspend fun createCollection(name: String, request: CreateCollectionRequest) {
        execute(name) {
            client.put("/collections/${encode(name)}") { setBody(request) }
        }
    }

    override suspend fun deleteCollection(name: String) {
        execute(name) {
            client.delete("/collections/${encode(name)}")
        }
    }

    override suspend fun upsert(name: String, points: List<PointStruct>, wait: Boolean) {
        if (points.isEmpty()) return
        // Split into batches to stay under Qdrant's 32 MiB REST payload cap.
        for (batch in points.chunked(upsertBatchSize)) {
            execute(name) {
                client.put("/collections/${encode(name)}/points") {
                    parameter("wait", wait)
                    setBody(UpsertRequest(batch))
                }
            }
        }
    }

    override fun close() {
        client.close()
    }

    private suspend fun execute(collection: String, call: suspend () -> HttpResponse) {
        withContext(config.dispatcher) {
            val response = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                throw KdrantException.Timeout("Request to Qdrant timed out", e)
            } catch (e: IOException) {
                throw KdrantException.Transport("Failed to reach Qdrant at ${config.host}:${config.port}", e)
            }
            ensureSuccess(response, collection)
        }
    }

    private suspend fun ensureSuccess(response: HttpResponse, collection: String) {
        if (response.status.isSuccess()) return
        val message = errorMessage(response)
        throw when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                KdrantException.Unauthorized(message ?: "Unauthorized")
            response.status == HttpStatusCode.NotFound ->
                KdrantException.CollectionNotFound(collection)
            response.status.value in 400..499 ->
                KdrantException.InvalidRequest(message ?: "Bad request: ${response.status}")
            else ->
                KdrantException.Transport(message ?: "Qdrant server error: ${response.status}")
        }
    }

    /**
     * Best-effort extraction of Qdrant's `{"status":{"error":"..."}}` error message.
     * Uses try/catch (not runCatching) so a [CancellationException] while reading the body
     * propagates instead of being swallowed.
     */
    private suspend fun errorMessage(response: HttpResponse): String? =
        try {
            val text = response.bodyAsText()
            val status = KdrantJson.parseToJsonElement(text).jsonObject["status"]
            status?.jsonObject?.get("error")?.jsonPrimitive?.content ?: text.ifBlank { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun encode(name: String): String =
        URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
}
