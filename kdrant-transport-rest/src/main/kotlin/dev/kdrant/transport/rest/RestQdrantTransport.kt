@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfig
import dev.kdrant.KdrantException
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.AliasDescription
import dev.kdrant.model.AliasOperation
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.FacetHit
import dev.kdrant.model.Filter
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SnapshotPriority
import dev.kdrant.model.UpdateCollectionRequest
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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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
    private val maxUpsertBytes: Int = DEFAULT_MAX_UPSERT_BYTES,
    private val logLevel: LogLevel? = null,
    private val logger: Logger? = null,
    private val configureClient: (HttpClientConfig<*>.() -> Unit)? = null,
) : QdrantTransport {

    init {
        require(upsertBatchSize > 0) { "upsertBatchSize must be > 0, was $upsertBatchSize" }
        require(maxUpsertBytes > 0) { "maxUpsertBytes must be > 0, was $maxUpsertBytes" }
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
            config.connectTimeout?.let { connectTimeoutMillis = it.inWholeMilliseconds }
            config.socketTimeout?.let { socketTimeoutMillis = it.inWholeMilliseconds }
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
        logLevel?.let { level ->
            // Capture the constructor param here: inside install(Logging) { } an unqualified `logger`
            // would resolve to the plugin config's own non-null logger, not ours.
            val configuredLogger = logger
            install(Logging) {
                this.level = level
                configuredLogger?.let { this.logger = it }
                // Never let the API key reach the logs, even at HEADERS/ALL level.
                sanitizeHeader { header -> header.equals("api-key", ignoreCase = true) }
            }
        }
        // Applied last so callers can install plugins (metrics, tracing), tune the CIO engine, or
        // override any default set above.
        configureClient?.invoke(this)
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

    override suspend fun updateCollection(name: String, request: UpdateCollectionRequest) {
        execute(name) {
            client.patch("/collections/${encode(name)}") { setBody(request) }
        }
    }

    override suspend fun upsert(name: String, points: List<PointStruct>, wait: Boolean) {
        if (points.isEmpty()) return
        upsertStreaming(name, points.asFlow(), wait)
    }

    override suspend fun upsert(name: String, points: Flow<PointStruct>, wait: Boolean) {
        upsertStreaming(name, points, wait)
    }

    /**
     * Buffers points into batches bounded by BOTH the point count ([upsertBatchSize]) and the serialized
     * size ([maxUpsertBytes], so Qdrant's ~32 MiB REST cap is respected even for high-dimensional vectors),
     * then PUTs each batch. Each point is serialized exactly once and the batch body is the concatenation
     * of those fragments (no re-serialization). The size bound uses the JSON character length, a close
     * proxy for UTF-8 bytes on numeric-vector-dominated payloads.
     */
    private suspend fun upsertStreaming(name: String, points: Flow<PointStruct>, wait: Boolean) {
        val batch = ArrayList<String>()
        var bytes = 0
        points.collect { point ->
            val json = KdrantJson.encodeToString(PointStruct.serializer(), point)
            // A single point over the cap can't be split; flush what we have, then send it alone.
            if (batch.isNotEmpty() && (batch.size >= upsertBatchSize || bytes + json.length > maxUpsertBytes)) {
                flushUpsert(name, batch, wait)
                batch.clear()
                bytes = 0
            }
            batch.add(json)
            bytes += json.length
        }
        if (batch.isNotEmpty()) flushUpsert(name, batch, wait)
    }

    /** Sends one upsert batch of pre-serialized point fragments (`PUT /collections/{name}/points`). */
    private suspend fun flushUpsert(name: String, pointsJson: List<String>, wait: Boolean) {
        val body = pointsJson.joinToString(separator = ",", prefix = """{"points":[""", postfix = "]}")
        execute(name) {
            client.put("/collections/${encode(name)}/points") {
                parameter("wait", wait)
                setBody(TextContent(body, ContentType.Application.Json))
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

    override suspend fun createPayloadIndex(name: String, field: String, schema: PayloadSchemaType, wait: Boolean) {
        execute(name) {
            client.put("/collections/${encode(name)}/index") {
                parameter("wait", wait)
                setBody(CreateFieldIndexRequest(field, schema))
            }
        }
    }

    override suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean) {
        execute(name) {
            client.delete("/collections/${encode(name)}/index/${encode(field)}") { parameter("wait", wait) }
        }
    }

    override suspend fun setPayload(name: String, payload: Payload, selector: DeleteSelector, key: String?, wait: Boolean) {
        val body = buildJsonObject {
            put("payload", payload)
            putSelector(selector)
            key?.let { put("key", JsonPrimitive(it)) }
        }
        execute(name) {
            client.post("/collections/${encode(name)}/points/payload") { parameter("wait", wait); setBody(body) }
        }
    }

    override suspend fun overwritePayload(name: String, payload: Payload, selector: DeleteSelector, wait: Boolean) {
        val body = buildJsonObject { put("payload", payload); putSelector(selector) }
        execute(name) {
            client.put("/collections/${encode(name)}/points/payload") { parameter("wait", wait); setBody(body) }
        }
    }

    override suspend fun deletePayload(name: String, keys: List<String>, selector: DeleteSelector, wait: Boolean) {
        val body = buildJsonObject {
            put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
            putSelector(selector)
        }
        execute(name) {
            client.post("/collections/${encode(name)}/points/payload/delete") { parameter("wait", wait); setBody(body) }
        }
    }

    override suspend fun clearPayload(name: String, selector: DeleteSelector, wait: Boolean) {
        val body = buildJsonObject { putSelector(selector) }
        execute(name) {
            client.post("/collections/${encode(name)}/points/payload/clear") { parameter("wait", wait); setBody(body) }
        }
    }

    override suspend fun updateVectors(name: String, points: List<PointVectors>, wait: Boolean) {
        execute(name) {
            client.put("/collections/${encode(name)}/points/vectors") {
                parameter("wait", wait)
                setBody(UpdateVectorsRequest(points))
            }
        }
    }

    override suspend fun deleteVectors(name: String, vectors: List<String>, selector: DeleteSelector, wait: Boolean) {
        val body = buildJsonObject {
            put("vector", JsonArray(vectors.map { JsonPrimitive(it) }))
            putSelector(selector)
        }
        execute(name) {
            client.post("/collections/${encode(name)}/points/vectors/delete") { parameter("wait", wait); setBody(body) }
        }
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

    override suspend fun updateAliases(operations: List<AliasOperation>, timeout: Int?) {
        execute {
            client.post("/collections/aliases") {
                timeout?.let { parameter("timeout", it) }
                setBody(ChangeAliasesRequest(operations))
            }
        }
    }

    override suspend fun listAliases(): List<AliasDescription> {
        val response = execute { client.get("/aliases") }
        return decodeBody(response) { it.body<AliasesResponse>().result.aliases }
    }

    override suspend fun listCollectionAliases(name: String): List<AliasDescription> {
        val response = execute(name) { client.get("/collections/${encode(name)}/aliases") }
        return decodeBody(response) { it.body<AliasesResponse>().result.aliases }
    }

    override suspend fun healthz(): Boolean = probe("/healthz")

    override suspend fun readyz(): Boolean = probe("/readyz")

    override suspend fun livez(): Boolean = probe("/livez")

    override suspend fun listCollections(): List<CollectionDescription> {
        val response = execute { client.get("/collections") }
        return decodeBody(response) { it.body<CollectionsListResponse>().result.collections }
    }

    override suspend fun telemetry(): JsonObject {
        val response = execute { client.get("/telemetry") }
        return decodeBody(response) { it.body<JsonObject>()["result"]?.jsonObject ?: JsonObject(emptyMap()) }
    }

    override suspend fun metrics(): String {
        val response = execute { client.get("/metrics") }
        return decodeBody(response) { it.bodyAsText() }
    }

    override suspend fun listIssues(): JsonElement {
        val response = execute { client.get("/issues") }
        return decodeBody(response) { it.body<JsonObject>()["result"] ?: JsonNull }
    }

    override suspend fun clearIssues() {
        execute { client.delete("/issues") }
    }

    override suspend fun facet(name: String, key: String, filter: Filter?, limit: Int?, exact: Boolean): List<FacetHit> {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/facet") {
                setBody(FacetRequest(key = key, limit = limit, filter = filter, exact = exact.takeIf { it }))
            }
        }
        return decodeBody(response) { it.body<FacetResponse>().result.hits }
    }

    override suspend fun searchMatrixPairs(name: String, request: SearchMatrixRequest): SearchMatrixPairs {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/search/matrix/pairs") { setBody(request) }
        }
        return decodeBody(response) { it.body<MatrixPairsResponse>().result }
    }

    override suspend fun searchMatrixOffsets(name: String, request: SearchMatrixRequest): SearchMatrixOffsets {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/points/search/matrix/offsets") { setBody(request) }
        }
        return decodeBody(response) { it.body<MatrixOffsetsResponse>().result }
    }

    override suspend fun createSnapshot(name: String, wait: Boolean): SnapshotDescription {
        val response = execute(name) {
            client.post("/collections/${encode(name)}/snapshots") { parameter("wait", wait) }
        }
        return decodeBody(response) { it.body<SnapshotResponse>().result }
    }

    override suspend fun listSnapshots(name: String): List<SnapshotDescription> {
        val response = execute(name) { client.get("/collections/${encode(name)}/snapshots") }
        return decodeBody(response) { it.body<SnapshotListResponse>().result }
    }

    override suspend fun deleteSnapshot(name: String, snapshotName: String, wait: Boolean) {
        execute(name) {
            client.delete("/collections/${encode(name)}/snapshots/${encode(snapshotName)}") { parameter("wait", wait) }
        }
    }

    override suspend fun recoverSnapshot(
        name: String,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        execute(name) {
            client.put("/collections/${encode(name)}/snapshots/recover") {
                parameter("wait", wait)
                setBody(SnapshotRecoverRequest(location, priority, checksum))
            }
        }
    }

    override fun downloadSnapshot(name: String, snapshotName: String): Flow<ByteArray> =
        downloadStream("/collections/${encode(name)}/snapshots/${encode(snapshotName)}", name)

    override suspend fun uploadSnapshot(
        name: String,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        coroutineScope {
            // Bridge the caller's Flow into a ByteReadChannel that Ktor streams as the multipart file part.
            val snapshotChannel = writer { data.collect { channel.writeFully(it) } }.channel
            val parts = formData {
                append(
                    key = "snapshot",
                    value = ChannelProvider { snapshotChannel },
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"snapshot.snapshot\"")
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    },
                )
            }
            execute(name) {
                client.post("/collections/${encode(name)}/snapshots/upload") {
                    parameter("wait", wait)
                    priority?.let { parameter("priority", it.toWireName()) }
                    checksum?.let { parameter("checksum", it) }
                    setBody(MultiPartFormDataContent(parts))
                }
            }
        }
    }

    override suspend fun createStorageSnapshot(wait: Boolean): SnapshotDescription {
        val response = execute { client.post("/snapshots") { parameter("wait", wait) } }
        return decodeBody(response) { it.body<SnapshotResponse>().result }
    }

    override suspend fun listStorageSnapshots(): List<SnapshotDescription> {
        val response = execute { client.get("/snapshots") }
        return decodeBody(response) { it.body<SnapshotListResponse>().result }
    }

    override suspend fun deleteStorageSnapshot(snapshotName: String, wait: Boolean) {
        execute { client.delete("/snapshots/${encode(snapshotName)}") { parameter("wait", wait) } }
    }

    override fun downloadStorageSnapshot(snapshotName: String): Flow<ByteArray> =
        downloadStream("/snapshots/${encode(snapshotName)}", collection = null)

    /**
     * Streams a snapshot download as a cold [Flow], holding the HTTP response open for the lifetime of
     * the collection so nothing is buffered in memory. Runs on [config.dispatcher].
     */
    private fun downloadStream(path: String, collection: String?): Flow<ByteArray> =
        channelFlow {
            try {
                client.prepareGet(path).execute { response ->
                    ensureSuccess(response, collection)
                    val bytes = response.bodyAsChannel()
                    while (!bytes.isClosedForRead) {
                        val packet = bytes.readRemaining(SNAPSHOT_CHUNK_BYTES.toLong())
                        while (!packet.exhausted()) {
                            send(packet.readByteArray())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                throw KdrantException.Timeout("Request to Qdrant timed out", e)
            } catch (e: IOException) {
                throw KdrantException.Transport("Failed to reach Qdrant at ${config.host}:${config.port}", e)
            }
        }.flowOn(config.dispatcher)

    override fun close() {
        client.close()
    }

    /** Runs a call not scoped to a collection (service, aliases list, storage snapshots). */
    private suspend fun execute(call: suspend () -> HttpResponse): HttpResponse =
        execute(collection = null, call = call)

    private suspend fun execute(collection: String?, call: suspend () -> HttpResponse): HttpResponse =
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

    /**
     * GETs a Kubernetes-style health probe: `true` on a 2xx, `false` on any other status (so a
     * "not ready" 503 is a signal, not an exception). Still throws when the server is unreachable.
     */
    private suspend fun probe(path: String): Boolean =
        withContext(config.dispatcher) {
            val response = try {
                client.get(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                throw KdrantException.Timeout("Request to Qdrant timed out", e)
            } catch (e: IOException) {
                throw KdrantException.Transport("Failed to reach Qdrant at ${config.host}:${config.port}", e)
            }
            response.status.isSuccess()
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

    private suspend fun ensureSuccess(response: HttpResponse, collection: String?) {
        if (response.status.isSuccess()) return
        val message = errorMessage(response)
        throw when (response.status.value) {
            401, 403 -> KdrantException.Unauthorized(message ?: "Unauthorized")
            404 ->
                if (collection != null) {
                    KdrantException.CollectionNotFound(collection, message)
                } else {
                    KdrantException.InvalidRequest(message ?: "Not found (HTTP 404)")
                }
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

/** Default soft cap on an upsert batch's serialized size — under Qdrant's ~32 MiB REST limit, with margin. */
internal const val DEFAULT_MAX_UPSERT_BYTES: Int = 30 * 1024 * 1024

/** Chunk size (bytes) for streaming a snapshot download. */
private const val SNAPSHOT_CHUNK_BYTES: Int = 64 * 1024

/** Wire form of a [SnapshotPriority] for use as a query parameter (the body uses the enum's serializer). */
private fun SnapshotPriority.toWireName(): String = when (this) {
    SnapshotPriority.NO_SYNC -> "no_sync"
    SnapshotPriority.SNAPSHOT -> "snapshot"
    SnapshotPriority.REPLICA -> "replica"
}

/** Adds the `points` or `filter` selector to a payload/vector mutation body. */
private fun JsonObjectBuilder.putSelector(selector: DeleteSelector) {
    when (selector) {
        is DeleteSelector.Ids ->
            put("points", JsonArray(selector.ids.map { KdrantJson.encodeToJsonElement(PointId.serializer(), it) }))
        is DeleteSelector.ByFilter ->
            put("filter", KdrantJson.encodeToJsonElement(Filter.serializer(), selector.filter))
    }
}
