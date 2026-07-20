@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfig
import dev.kdrant.KdrantException
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.Filter
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        install(HttpRequestRetry) {
            maxRetries = config.maxRetries
            // Retry only transient server states and I/O errors — never a 4xx (except 429) or a timeout.
            retryIf { _, response -> response.status.value in RETRYABLE_STATUS_CODES }
            retryOnExceptionIf { _, cause -> cause is IOException && cause !is HttpRequestTimeoutException }
            exponentialDelay(
                base = 2.0,
                baseDelayMs = config.retryBaseDelay.inWholeMilliseconds,
                maxDelayMs = config.retryMaxDelay.inWholeMilliseconds,
                randomizationMs = config.retryBaseDelay.inWholeMilliseconds,
                respectRetryAfterHeader = true,
            )
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

    override suspend fun query(name: String, request: SearchRequest): List<ScoredPoint> {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/query") { setBody(request) }
        }
        return decodeBody(response) { it.body<QueryResponse>().result.points }
    }

    override suspend fun queryBatch(name: String, requests: List<SearchRequest>): List<List<ScoredPoint>> {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/query/batch") { setBody(BatchQueryRequest(requests)) }
        }
        return decodeBody(response) { resp -> resp.body<BatchQueryResponse>().result.map { it.points } }
    }

    override suspend fun queryGroups(name: String, request: SearchGroupsRequest): List<PointGroup> {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/query/groups") { setBody(request) }
        }
        return decodeBody(response) { it.body<GroupsResponse>().result.groups }
    }

    override suspend fun scroll(name: String, request: ScrollRequest): ScrollPage {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/scroll") { setBody(request) }
        }
        return decodeBody(response) { it.body<ScrollResponse>().result }
    }

    override suspend fun delete(name: String, selector: DeleteSelector, wait: Boolean) {
        val body: JsonObject = when (selector) {
            is DeleteSelector.Ids -> buildJsonObject {
                put("points", JsonArray(selector.ids.map { KdrantJson.encodeToJsonElement(PointId.serializer(), it) }))
            }
            is DeleteSelector.ByFilter -> buildJsonObject {
                put("filter", KdrantJson.encodeToJsonElement(Filter.serializer(), selector.filter))
            }
        }
        execute(name) {
            client.post("/collections/${encode(name)}/points/delete") {
                parameter("wait", wait)
                setBody(body)
            }
        }
    }

    override suspend fun collectionExists(name: String): Boolean =
        try {
            val response = execute(name) { client.get("/collections/${encode(name)}/exists") }
            decodeBody(response) { it.body<ExistsResponse>().result.exists }
        } catch (e: KdrantException.CollectionNotFound) {
            // The exists endpoint returns 200 {"exists":false} for a missing collection; a 404 here
            // (e.g. an older server without the endpoint) still means "not present" per the contract.
            false
        }

    override suspend fun getCollection(name: String): CollectionInfo {
        val response = execute(name) { client.get("/collections/${encode(name)}") }
        return decodeBody(response) { it.body<CollectionInfoResponse>().result }
    }

    override suspend fun count(name: String, filter: Filter?, exact: Boolean): Long {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/count") { setBody(CountRequest(filter, exact)) }
        }
        return decodeBody(response) { it.body<CountResponse>().result.count }
    }

    override suspend fun retrieve(
        name: String,
        ids: List<PointId>,
        withPayload: WithPayload?,
        withVector: Boolean?,
    ): List<Record> {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points") {
                setBody(PointRequest(ids, withPayload, withVector))
            }
        }
        return decodeBody(response) { it.body<RetrieveResponse>().result }
    }

    override fun close() {
        client.close()
    }

    private suspend fun execute(collection: String, call: suspend () -> HttpResponse): HttpResponse =
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
            response
        }

    /** Decodes a success response body on [config.dispatcher], mapping parse failures to [KdrantException]. */
    private suspend fun <T> decodeBody(response: HttpResponse, decode: suspend (HttpResponse) -> T): T =
        withContext(config.dispatcher) {
            try {
                decode(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw KdrantException.Transport("Failed to parse the Qdrant response", e)
            }
        }

    private suspend fun ensureSuccess(response: HttpResponse, collection: String) {
        if (response.status.isSuccess()) return
        val message = errorMessage(response)
        throw when (response.status.value) {
            401, 403 -> KdrantException.Unauthorized(message ?: "Unauthorized")
            404 -> KdrantException.CollectionNotFound(collection, message)
            408 -> KdrantException.Timeout(message ?: "Qdrant reported a request timeout (HTTP 408)")
            409 -> KdrantException.AlreadyExists(message ?: "Resource already exists (HTTP 409)")
            429 -> KdrantException.RateLimited(retryAfter(response), message ?: "Rate limited by Qdrant (HTTP 429)")
            503 -> KdrantException.ServiceUnavailable(message ?: "Qdrant is temporarily unavailable (HTTP 503)")
            in 400..499 -> KdrantException.InvalidRequest(message ?: "Bad request: ${response.status}")
            in 500..599 -> KdrantException.ServerError(message ?: "Qdrant server error: ${response.status}")
            else -> KdrantException.Transport(message ?: "Unexpected response: ${response.status}")
        }
    }

    /** Parses the `Retry-After` header (delta-seconds form) into a [Duration], if present and numeric. */
    private fun retryAfter(response: HttpResponse): Duration? =
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

    /**
     * Best-effort extraction of Qdrant's `{"status":{"error":"..."}}` error message.
     * Uses try/catch (not runCatching) so a [CancellationException] while reading the body
     * propagates instead of being swallowed.
     */
    private suspend fun errorMessage(response: HttpResponse): String? {
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val statusError = runCatching {
            KdrantJson.parseToJsonElement(text).jsonObject["status"]?.jsonObject?.get("error")?.jsonPrimitive?.content
        }.getOrNull()
        return statusError ?: text.ifBlank { null }
    }

    private fun encode(name: String): String =
        URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
}

/** HTTP statuses worth retrying: rate-limit plus transient gateway/service errors. */
private val RETRYABLE_STATUS_CODES: Set<Int> = setOf(429, 502, 503, 504)
