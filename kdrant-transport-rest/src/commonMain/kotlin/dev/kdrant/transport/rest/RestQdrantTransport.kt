@file:OptIn(InternalKdrantApi::class)

package dev.kdrant.transport.rest

import dev.kdrant.KdrantConfig
import dev.kdrant.KdrantException
import dev.kdrant.internal.InternalKdrantApi
import dev.kdrant.internal.KdrantJson
import dev.kdrant.model.AliasDescription
import dev.kdrant.model.AliasOperation
import dev.kdrant.model.ClusterOperation
import dev.kdrant.model.CollectionClusterInfo
import dev.kdrant.model.CollectionDescription
import dev.kdrant.model.CollectionInfo
import dev.kdrant.model.CreateCollectionRequest
import dev.kdrant.model.CreateShardKeyRequest
import dev.kdrant.model.DeleteSelector
import dev.kdrant.model.FacetHit
import dev.kdrant.model.Filter
import dev.kdrant.model.Payload
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.PayloadSchemaType
import dev.kdrant.model.PointGroup
import dev.kdrant.model.PointId
import dev.kdrant.model.PointStruct
import dev.kdrant.model.PointVectors
import dev.kdrant.model.PointsUpdateOperation
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import dev.kdrant.model.ScrollPage
import dev.kdrant.model.ScrollRequest
import dev.kdrant.model.SearchGroupsRequest
import dev.kdrant.model.SearchMatrixOffsets
import dev.kdrant.model.SearchMatrixPairs
import dev.kdrant.model.SearchMatrixRequest
import dev.kdrant.model.SearchRequest
import dev.kdrant.model.ShardKey
import dev.kdrant.model.SnapshotDescription
import dev.kdrant.model.SnapshotPriority
import dev.kdrant.model.UpdateCollectionRequest
import dev.kdrant.model.WithPayload
import dev.kdrant.transport.QdrantTransport
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
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
import kotlinx.io.IOException
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
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * REST/Ktor engine: the default Kdrant transport.
 *
 * Owns a Ktor [HttpClient] preconfigured with the base URL, credentials, JSON content negotiation,
 * and a request timeout. Translates HTTP and transport failures into a [KdrantException] and always
 * re-throws [CancellationException]. The engine is chosen per target by [platformHttpClient]; every
 * line below this one is the same on all of them.
 *
 * @param engine optional engine override, used by tests to plug in a `MockEngine`; production
 *   code leaves it null and gets the target's engine.
 * @param upsertBatchSize maximum points per upsert request; larger batches are split to stay
 *   under Qdrant's 32 MiB REST payload cap.
 * @param maxConnectionsPerRoute connection-pool size per host on the JVM engine; `null` keeps the
 *   engine default. Ignored when [engine] is supplied, since the pool belongs to the engine, and on
 *   the native engines, which do not expose one.
 * @param keepAliveTime how long the JVM engine keeps an idle pooled connection; `null` keeps the
 *   engine default.
 * @param requestId called to produce the `X-Request-Id` value of each request; `null` sends no header.
 */
// One class on purpose: it is the whole wire mapping for one protocol, and every method is the same
// three lines around a different endpoint. Splitting it by topic would hide that uniformity behind an
// index of files without removing a line.
@Suppress("LargeClass", "TooManyFunctions")
internal class RestQdrantTransport(
    private val config: KdrantConfig,
    engine: HttpClientEngine? = null,
    private val upsertBatchSize: Int = 1000,
    private val maxUpsertBytes: Int = DEFAULT_MAX_UPSERT_BYTES,
    private val logLevel: LogLevel? = null,
    private val logger: Logger? = null,
    private val maxConnectionsPerRoute: Int? = null,
    private val keepAliveTime: Duration? = null,
    private val requestId: (() -> String)? = null,
    private val configureClient: (HttpClientConfig<*>.() -> Unit)? = null,
) : QdrantTransport {

    init {
        require(upsertBatchSize > 0) { "upsertBatchSize must be > 0, was $upsertBatchSize" }
        require(maxUpsertBytes > 0) { "maxUpsertBytes must be > 0, was $maxUpsertBytes" }
        maxConnectionsPerRoute?.let {
            require(it > 0) { "maxConnectionsPerRoute must be > 0, was $it" }
        }
        keepAliveTime?.let { require(it.isPositive()) { "keepAliveTime must be positive, was $it" } }
    }

    private val client: HttpClient =
        if (engine != null) {
            HttpClient(engine) { applyCommonConfig() }
        } else {
            platformHttpClient(maxConnectionsPerRoute, keepAliveTime, config.trustAnchors) { applyCommonConfig() }
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
            config.apiKey?.let { headers.append(API_KEY_HEADER, it) }
            // Qdrant accepts a JWT under either header; Authorization is the one every proxy,
            // gateway and log scrubber in front of it already knows to treat as a secret.
            config.bearerToken?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
            // Evaluated per request, so a caller can hand back the id its own tracing context carries.
            requestId?.let { headers.append(REQUEST_ID_HEADER, it()) }
            contentType(ContentType.Application.Json)
        }
        logLevel?.let { level ->
            // Capture the constructor param here: inside install(Logging) { } an unqualified `logger`
            // would resolve to the plugin config's own non-null logger, not ours.
            val configuredLogger = logger
            install(Logging) {
                this.level = level
                configuredLogger?.let { this.logger = it }
                // Never let a credential reach the logs, even at HEADERS/ALL level.
                sanitizeHeader { header ->
                    header.equals(API_KEY_HEADER, ignoreCase = true) ||
                        header.equals(HttpHeaders.Authorization, ignoreCase = true)
                }
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
        // What the server acknowledged, and what it was given. A caller who is only told that the call
        // failed cannot tell "nothing was written" from "the first four hundred thousand were", and the
        // two call for opposite recoveries.
        var applied = 0
        points.collect { point ->
            val json = KdrantJson.encodeToString(PointStruct.serializer(), point)
            // A single point over the cap can't be split; flush what we have, then send it alone.
            if (batch.isNotEmpty() && (batch.size >= upsertBatchSize || bytes + json.length > maxUpsertBytes)) {
                flushUpsert(name, batch, wait, applied)
                applied += batch.size
                batch.clear()
                bytes = 0
            }
            batch.add(json)
            bytes += json.length
        }
        if (batch.isNotEmpty()) flushUpsert(name, batch, wait, applied)
    }

    /**
     * Sends one upsert batch of pre-serialized point fragments (`PUT /collections/{name}/points`).
     *
     * A failure after an earlier batch landed is reported as [KdrantException.PartiallyApplied] naming
     * how many points were written, rather than as the underlying failure alone. [alreadyApplied] is
     * the count the server acknowledged before this batch; the first batch of a call has nothing behind
     * it and its failure is reported unchanged, because nothing was partially applied.
     */
    private suspend fun flushUpsert(
        name: String,
        pointsJson: List<String>,
        wait: Boolean,
        alreadyApplied: Int,
    ) {
        val body = pointsJson.joinToString(separator = ",", prefix = """{"points":[""", postfix = "]}")
        try {
            execute(name) {
                client.put("/collections/${encode(name)}/points") {
                    parameter("wait", wait)
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            }
        } catch (e: KdrantException) {
            if (alreadyApplied == 0) throw e
            throw KdrantException.PartiallyApplied(alreadyApplied, e)
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

    override suspend fun createPayloadIndex(name: String, field: String, params: PayloadIndexParams, wait: Boolean) {
        execute(name) {
            client.put("/collections/${encode(name)}/index") {
                parameter("wait", wait)
                setBody(CreateFieldIndexParamsRequest(field, params))
            }
        }
    }

    override suspend fun deletePayloadIndex(name: String, field: String, wait: Boolean) {
        execute(name) {
            client.delete("/collections/${encode(name)}/index/${encode(field)}") { parameter("wait", wait) }
        }
    }

    override suspend fun setPayload(
        name: String,
        payload: Payload,
        selector: DeleteSelector,
        key: String?,
        wait: Boolean,
    ) {
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

    override suspend fun batchUpdate(name: String, operations: List<PointsUpdateOperation>, wait: Boolean) {
        if (operations.isEmpty()) return
        val body = buildJsonObject { put("operations", JsonArray(operations.map(::operationJson))) }
        execute(name) {
            client.post("/collections/${encode(name)}/points/batch") { parameter("wait", wait); setBody(body) }
        }
    }

    // --- Cluster & sharding (M32) ---

    override suspend fun collectionClusterInfo(name: String): CollectionClusterInfo {
        val response = execute(name) { client.get("/collections/${encode(name)}/cluster") }
        return decodeBody(response) { it.body<CollectionClusterResponse>().result }
    }

    override suspend fun updateCollectionCluster(name: String, operation: ClusterOperation, timeout: Int?) {
        execute(name) {
            client.post("/collections/${encode(name)}/cluster") {
                timeout?.let { parameter("timeout", it) }
                setBody(KdrantJson.encodeToJsonElement(ClusterOperation.serializer(), operation))
            }
        }
    }

    override suspend fun createShardKey(name: String, request: CreateShardKeyRequest, timeout: Int?) {
        execute(name) {
            client.put("/collections/${encode(name)}/shards") {
                timeout?.let { parameter("timeout", it) }
                setBody(request)
            }
        }
    }

    override suspend fun deleteShardKey(name: String, shardKey: ShardKey, timeout: Int?) {
        val body = buildJsonObject {
            put("shard_key", KdrantJson.encodeToJsonElement(ShardKey.serializer(), shardKey))
        }
        execute(name) {
            client.post("/collections/${encode(name)}/shards/delete") {
                timeout?.let { parameter("timeout", it) }
                setBody(body)
            }
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

    override suspend fun facet(
        name: String,
        key: String,
        filter: Filter?,
        limit: Int?,
        exact: Boolean,
    ): List<FacetHit> {
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

    // --- Shard-scope snapshots (M36) ---

    override suspend fun createShardSnapshot(name: String, shardId: Int, wait: Boolean): SnapshotDescription {
        val response = execute(name) {
            client.post("${shardSnapshots(name, shardId)}") { parameter("wait", wait) }
        }
        return decodeBody(response) { it.body<SnapshotResponse>().result }
    }

    override suspend fun listShardSnapshots(name: String, shardId: Int): List<SnapshotDescription> {
        val response = execute(name) { client.get("${shardSnapshots(name, shardId)}") }
        return decodeBody(response) { it.body<SnapshotListResponse>().result }
    }

    override suspend fun deleteShardSnapshot(name: String, shardId: Int, snapshotName: String, wait: Boolean) {
        execute(name) {
            client.delete("${shardSnapshots(name, shardId)}/${encode(snapshotName)}") { parameter("wait", wait) }
        }
    }

    override suspend fun recoverShardSnapshot(
        name: String,
        shardId: Int,
        location: String,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        execute(name) {
            client.put("${shardSnapshots(name, shardId)}/recover") {
                parameter("wait", wait)
                setBody(SnapshotRecoverRequest(location, priority, checksum))
            }
        }
    }

    override fun downloadShardSnapshot(name: String, shardId: Int, snapshotName: String): Flow<ByteArray> =
        downloadStream("${shardSnapshots(name, shardId)}/${encode(snapshotName)}", name)

    override suspend fun uploadShardSnapshot(
        name: String,
        shardId: Int,
        data: Flow<ByteArray>,
        priority: SnapshotPriority?,
        checksum: String?,
        wait: Boolean,
    ) {
        coroutineScope {
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
                client.post("${shardSnapshots(name, shardId)}/upload") {
                    parameter("wait", wait)
                    priority?.let { parameter("priority", it.toWireName()) }
                    checksum?.let { parameter("checksum", it) }
                    setBody(MultiPartFormDataContent(parts))
                }
            }
        }
    }

    /** Shard ids are server-assigned integers, so they need no encoding; the collection name does. */
    private fun shardSnapshots(name: String, shardId: Int): String =
        "/collections/${encode(name)}/shards/$shardId/snapshots"

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
            } catch (e: KdrantException) {
                throw e
            } catch (e: Throwable) {
                // A refused certificate is not an IOException on every platform: the JVM raises
                // CertPathValidatorException and CertificateException, which used to escape this seam
                // and reach a caller who was told every failure here is a KdrantException. Widening the
                // catch is what makes that sentence true; the cause is kept, so the certificate problem
                // is still readable underneath.
                throw KdrantException.Transport(
                    "Failed to reach Qdrant at ${config.host}:${config.port}: ${e.message ?: e::class.simpleName}",
                    e,
                )
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
            401 -> KdrantException.Unauthorized(message ?: "Unauthorized")
            403 -> refused(collection, message)
            404 -> notFound(collection, message)
            408 -> KdrantException.Timeout(message ?: "Qdrant reported a request timeout (HTTP 408)")
            409 -> KdrantException.AlreadyExists(message ?: "Resource already exists (HTTP 409)")
            429 -> KdrantException.RateLimited(retryAfter(response), message ?: "Rate limited by Qdrant (HTTP 429)")
            503 -> KdrantException.ServiceUnavailable(message ?: "Qdrant is temporarily unavailable (HTTP 503)")
            in 400..499 -> clientError(collection, message, response)
            in 500..599 -> serverError(collection, message, response)
            else -> KdrantException.Transport(message ?: "Unexpected response: ${response.status}")
        }
    }

    /**
     * 401 is "who are you"; 403 is "you, specifically, may not". A scoped token refused on a write has
     * to be told apart from a missing key, because only one of them is a bug in the token rather than
     * in the deployment. Qdrant also answers 403 when the *node* may not write, which is a third thing
     * again: nothing is wrong with the credential and waiting is the fix.
     */
    private fun refused(collection: String?, message: String?): KdrantException =
        if (namesReadOnly(message)) {
            KdrantException.ReadOnly(collection, message)
        } else {
            KdrantException.Forbidden(collection, message)
        }

    private fun notFound(collection: String?, message: String?): KdrantException =
        if (collection != null) {
            KdrantException.CollectionNotFound(collection, message)
        } else {
            KdrantException.InvalidRequest(message ?: "Not found (HTTP 404)")
        }

    /**
     * A degraded cluster answers on both sides of the 4xx/5xx line depending on which check refused
     * first, so the status cannot decide which failure it is and the message has to.
     */
    private fun clientError(collection: String?, message: String?, response: HttpResponse): KdrantException = when {
        namesUnavailableShard(message) -> KdrantException.ShardUnavailable(collection, message)
        // Strict mode refuses a write over its disk or memory ceiling with a 4xx rather than a 403, so
        // the same state arrives on both sides of the auth line.
        namesReadOnly(message) -> KdrantException.ReadOnly(collection, message)
        else -> KdrantException.InvalidRequest(message ?: "Bad request: ${response.status}")
    }

    private fun serverError(collection: String?, message: String?, response: HttpResponse): KdrantException =
        if (namesUnavailableShard(message)) {
            KdrantException.ShardUnavailable(collection, message)
        } else {
            KdrantException.ServerError(message ?: "Qdrant server error: ${response.status}")
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

    private fun encode(name: String): String = encodePathSegment(name)
}

/**
 * Whether a refusal is the node declining to write rather than the credential being declined.
 *
 * Matched on the message because the status does not separate them — Qdrant answers 403 both when a
 * token may not write and when the node may not — and there is no machine-readable error code to key
 * on. Two wordings mean the same thing to a caller: the explicit read-only state, and a strict-mode
 * limit on disk or memory, which is the same event with the cause named.
 *
 * Deliberately substrings rather than exact strings, so the wording may change without turning a
 * read-only node back into an auth failure. When nothing matches, the mapping falls back to
 * [KdrantException.Forbidden] or [KdrantException.InvalidRequest], which is where these failures
 * landed before: an unrecognised message costs the caller nothing they had.
 */
internal fun namesReadOnly(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    if ("read-only" in text || "read only" in text || "readonly" in text) return true
    // Strict mode's disk and memory ceilings: writes refused, reads still served.
    val pressure = "disk usage" in text || "resident memory" in text || "memory usage" in text
    return pressure && ("exceed" in text || "limit" in text || "above" in text || "too high" in text)
}

/**
 * Whether a failure is a shard with no live replica rather than a bad request or a broken server.
 *
 * Qdrant answers this on both sides of the 4xx/5xx line depending on which check refused first — a
 * write rejected for not reaching the write consistency factor is a client error, a read that found no
 * replica is a server one — so the status cannot decide it either.
 */
internal fun namesUnavailableShard(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    return ("shard" in text || "replica" in text) &&
        ("not available" in text || "unavailable" in text || "no active" in text || "not enough" in text)
}

/**
 * Percent-encodes one path segment per RFC 3986: the unreserved set survives, everything else
 * becomes the percent-encoded UTF-8 bytes.
 *
 * `java.net.URLEncoder` used to do this and did it slightly wrong — it is form encoding, which is
 * why the call site had to rewrite `+` back to `%20` — and it is JVM-only, which is the reason it
 * had to go. A collection named `my docs` is still addressed as `my%20docs`.
 */
internal fun encodePathSegment(segment: String): String =
    buildString(segment.length) {
        for (byte in segment.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            if (isUnreserved(value)) {
                append(Char(value))
            } else {
                append('%')
                append(HEX_DIGITS[value shr 4])
                append(HEX_DIGITS[value and 0xF])
            }
        }
    }

/** `A-Z`, `a-z`, `0-9`, `-`, `.`, `_`, `~` — the characters RFC 3986 says never need encoding. */
private fun isUnreserved(byte: Int): Boolean =
    byte in 'A'.code..'Z'.code ||
        byte in 'a'.code..'z'.code ||
        byte in '0'.code..'9'.code ||
        byte == '-'.code ||
        byte == '.'.code ||
        byte == '_'.code ||
        byte == '~'.code

private const val HEX_DIGITS: String = "0123456789ABCDEF"

/** HTTP statuses worth retrying: rate-limit plus transient gateway/service errors. */
private val RETRYABLE_STATUS_CODES: Set<Int> = setOf(429, 502, 503, 504)

/** Default soft cap on an upsert batch's serialized size — under Qdrant's ~32 MiB REST limit, with margin. */
internal const val DEFAULT_MAX_UPSERT_BYTES: Int = 30 * 1024 * 1024

/** Correlation header, the spelling Qdrant and the common proxies in front of it log. */
private const val REQUEST_ID_HEADER: String = "X-Request-Id"

/** Qdrant's own header for the master key. */
private const val API_KEY_HEADER: String = "api-key"

/** Chunk size (bytes) for streaming a snapshot download. */
private const val SNAPSHOT_CHUNK_BYTES: Int = 64 * 1024

/** Wire form of a [SnapshotPriority] for use as a query parameter (the body uses the enum's serializer). */
private fun SnapshotPriority.toWireName(): String = when (this) {
    SnapshotPriority.NO_SYNC -> "no_sync"
    SnapshotPriority.SNAPSHOT -> "snapshot"
    SnapshotPriority.REPLICA -> "replica"
}

/** Adds the `points` or `filter` selector to a payload/vector mutation body. */
/**
 * One entry of a `points/batch` body: a single-key object naming the operation, whose value is the
 * same shape the standalone endpoint for that operation takes.
 */
private fun operationJson(operation: PointsUpdateOperation): JsonObject = buildJsonObject {
    when (operation) {
        is PointsUpdateOperation.Upsert -> putJsonObject("upsert") {
            put(
                "points",
                JsonArray(
                    operation.points.map {
                        KdrantJson.encodeToJsonElement(PointStruct.serializer(), it)
                    },
                ),
            )
        }
        is PointsUpdateOperation.Delete -> putJsonObject("delete") { putSelector(operation.selector) }
        is PointsUpdateOperation.SetPayload -> putJsonObject("set_payload") {
            put("payload", operation.payload)
            putSelector(operation.selector)
            operation.key?.let { put("key", JsonPrimitive(it)) }
        }
        is PointsUpdateOperation.OverwritePayload -> putJsonObject("overwrite_payload") {
            put("payload", operation.payload)
            putSelector(operation.selector)
        }
        is PointsUpdateOperation.DeletePayload -> putJsonObject("delete_payload") {
            put("keys", JsonArray(operation.keys.map { JsonPrimitive(it) }))
            putSelector(operation.selector)
        }
        is PointsUpdateOperation.ClearPayload -> putJsonObject("clear_payload") { putSelector(operation.selector) }
        is PointsUpdateOperation.UpdateVectors -> putJsonObject("update_vectors") {
            put(
                "points",
                JsonArray(operation.points.map { KdrantJson.encodeToJsonElement(PointVectors.serializer(), it) }),
            )
        }
        is PointsUpdateOperation.DeleteVectors -> putJsonObject("delete_vectors") {
            put("vector", JsonArray(operation.vectors.map { JsonPrimitive(it) }))
            putSelector(operation.selector)
        }
    }
}

private fun JsonObjectBuilder.putSelector(selector: DeleteSelector) {
    when (selector) {
        is DeleteSelector.Ids ->
            put("points", JsonArray(selector.ids.map { KdrantJson.encodeToJsonElement(PointId.serializer(), it) }))
        is DeleteSelector.ByFilter ->
            put("filter", KdrantJson.encodeToJsonElement(Filter.serializer(), selector.filter))
    }
}
