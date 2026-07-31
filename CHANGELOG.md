# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Cluster and sharding (M32), the gap the migration guide used to name as having no Kdrant equivalent.
  `collectionClusterInfo(name)` reads how a collection's shards are spread across peers, including the
  transfers in flight; `updateCollectionCluster(name, operation)` moves, replicates, aborts or drops a
  shard; and `createShardKey` / `deleteShardKey` manage custom sharding keys. The placement calls return
  once the transfer is **accepted**, not once it has finished, which the KDoc says rather than leaving
  it to be discovered.
- `ShardKey` and `shardKey` on `search` and `scroll`, so a query that concerns one region or one tenant
  reads that key's shards instead of all of them. A numeric key stays a number on the wire; quoting it
  would make Qdrant read it as a different key.
- `ReplicaState` decodes an unrecognized state from a newer Qdrant to `UNKNOWN` rather than failing the
  whole cluster-info response, the same tolerance `CollectionStatus` already had.
- Formula reranking and MMR, scoped in M16 and not shipped with it. `formula(expression)` rescores the
  candidates a `prefetch` produced with arithmetic over their score and payload: multiply by a
  popularity field, add a bonus for points matching a condition, decay by recency or by distance. The
  `Expression` AST covers Qdrant's full operator set, including the three decay curves and
  `geo_distance`. `mmr(diversity)` reranks a vector query for variety instead of letting ten results
  about the same thing crowd the top.
  Both are validated against Qdrant's published schema by the contract tests, and the bounds Qdrant
  documents (diversity in `0..1`, a positive decay scale, a midpoint in `0..1`) are checked where they
  are written rather than on the round trip.
- Shard-scope snapshots, deferred out of M20 when snapshots first shipped: `createShardSnapshot`,
  `listShardSnapshots`, `deleteShardSnapshot`, `recoverShardSnapshot`, plus streaming
  `downloadShardSnapshot` and `uploadShardSnapshot`. On a sharded collection the existing
  whole-collection snapshot is every shard at once, which on a large collection is the difference
  between a backup that fits in a window and one that does not. Shard ids come from
  `collectionClusterInfo`.

### Changed

- Releases publish to Maven Central only. The secondary publication to GitHub Packages is gone: it
  carried the same artifacts to a registry that requires authentication even for public packages, so it
  was a second place to keep in sync and no second way for anyone to depend on Kdrant. Versions up to
  and including `1.2.0` remain on GitHub Packages and are not withdrawn.

## [1.2.0] - 2026-07-31

Tier 6, complete. The framework adapters honour metadata filters, deployment scripts get
`ensureCollection`, an ordered `scroll` and `batchUpdate`, observability ships instead of being
reachable by hand, the wire format is held to Qdrant's own schema by contract tests, and switching
from the official client has a guide and measured numbers behind it.

**Upgrading is a recompile, not a jar swap.** `apiCheck` reads this release as additive, but new members
on `QdrantClient` and `QdrantTransport` break a class that implemented them against `1.1.0`, and the
fields added to `CollectionInfo`, `ScrollRequest` and `Record` change their generated `copy`. Source
stays compatible; see [STABILITY.md](STABILITY.md#what-may-still-change-in-a-minor).

### Added

- Metadata-filter translation for the framework adapters (M26). `kdrant-spring-ai` and `kdrant-langchain4j`
  used to throw on any filter expression, which meant a filtered RAG application was not the drop-in swap
  the modules advertised. Both now translate their framework's filter model into Kdrant's:
  `Filter.Expression.toKdrantFilter()` for Spring AI and `Filter.toKdrantFilter()` for LangChain4j, wired
  into `similaritySearch`, `VectorStore.delete(Expression)` and `EmbeddingStore.search`. Boolean chains
  flatten into a single `must` / `should` clause, comparisons pick Qdrant's numeric or RFC 3339 `range`
  variant from the value's runtime type, and Spring AI's `IS NULL` maps to `is_empty` (which, unlike
  Qdrant's `is_null`, also covers a missing key). A value Qdrant cannot express is rejected with an
  `IllegalArgumentException` rather than dropped, so a filter never silently widens a result set.
- `SearchBuilder.filter(Filter)` and `PrefetchBuilder.filter(Filter)`, plus
  `QdrantClient.delete(name, selector, wait)` — the entry points a translator needs to pass an
  already-built filter, alongside the existing DSL forms.
- `ensureCollection(name) { ... }` (M27): creates the collection if it is missing and otherwise checks
  that the one already there has the dense vector names, sizes and distances, and the sparse vector
  names, that were asked for. Returns whether it created the collection, absorbs a create that lost a
  race to another process, and fails loudly on a mismatch rather than leaving an application to
  discover the wrong vector size on its first upsert. Everything the server defaults (HNSW, optimizers,
  quantization) is deliberately not compared.
- The enriched `getCollection` read-back that check reads: `CollectionInfo.config` (vectors, sparse
  vectors, shard number, replication factor, on-disk payload) and `CollectionInfo.payloadSchema`. An
  index type a future Qdrant adds is kept as its wire string rather than failing the whole response.
- An ordered `scroll` (M27): `scroll("docs") { orderBy("ts", Direction.DESC) }`, plus `orderByDatetime`
  for RFC 3339 keys, `startFrom` to resume a partly consumed pass, and `Record.orderValue`. Qdrant
  returns no page cursor for an ordered scroll, so the client pages on the order value and drops the
  points a page repeats at the boundary; each point is still emitted exactly once. A scroll that cannot
  advance — more points tied on one order value than fit in a page — fails with a message saying so
  instead of silently truncating.
- `batchUpdate(name, wait) { ... }` (M27): one request applying an ordered, mixed sequence of point,
  vector and payload operations. Ordered but **not** transactional: a later operation sees the effect of
  an earlier one, but a failure part-way through leaves the earlier operations applied.
- `ScrollBuilder.filter(Filter)`, matching the search builders.

- **`kdrant-micrometer`** (M28), a new module: `configureClient = { kdrantMetrics(registry) }` times every
  request as `kdrant.requests`, tagged with the operation, HTTP method, status and outcome. The operation
  tag is the route template, not the URL — collection, field and snapshot names become placeholders, so a
  deployment with thousands of collections does not become thousands of time series.
- `X-Request-Id` correlation (M28): `Kdrant(host, requestId = { ... })` sets the header from the caller's
  own trace id, so a Kdrant call can be followed into Qdrant's logs. Off by default, since sending a new
  header on every request would change the bytes on the wire for everyone.
- Connection-pool settings on the REST engine factory (M28): `maxConnectionsPerRoute` and `keepAliveTime`
  are parameters of `Kdrant(...)`, not of `KdrantConfig`, which stays transport-neutral. This is where the
  pool settings declined on `KdrantConfig` land.

- Contract tests against Qdrant's OpenAPI schema (M29). Every request body the REST engine builds is
  captured from a real client call and validated against the schema Qdrant publishes for that endpoint,
  with unknown properties treated as failures. Qdrant's document is vendored under
  `kdrant-transport-rest/src/test/resources` and pinned to the version the CI matrix runs against, so
  refreshing it is how a wire change that would otherwise pass silently becomes a failing build.
- Kover coverage (M29), which the Kotlin 2.4 incompatibility had deferred. `./gradlew koverHtmlReport`
  covers the six published modules; CI runs it on JDK 17 and enforces a 75% line floor — a floor to
  catch a module arriving untested, not a number to inch towards. Current line coverage is 82.8%.
- SLSA build provenance on release (M29): the release workflow assembles the jars, attests them with
  `actions/attest-build-provenance`, and only then publishes, so the attestation covers the exact files
  that reach Maven Central and GitHub Packages.
- A [migration guide from `io.qdrant:client`](docs/migrating-from-qdrant-client.md) (M30), mapping the
  official client operation by operation, with the differences that actually bite: the port, the
  `ListenableFuture`-to-`suspend` shift, protobuf builders against the DSL, and where the official
  client is still the right tool.
- A dispatchable `Benchmarks` workflow (M30) that runs the JMH harness against a chosen Qdrant image on
  a clean runner and uploads the results, and the first
  [measured numbers](benchmarks/README.md#measured-latency) from it: `search` p50 1.97 ms / p99
  5.40 ms, `upsert` p50 3.37 ms / p99 9.81 ms against Qdrant `v1.18.2`. Published with the conditions
  they were taken under, including the ones that make them a floor rather than a capacity figure: no
  network between client and server, a 1 000-point collection, and no concurrency.
- The design rationale in [STABILITY.md](STABILITY.md) (M30) now states what a `1.x` upgrade actually
  guarantees: `QdrantClient` and `QdrantTransport` are interfaces to call rather than implement, and a
  field added to a public data class changes its generated `copy`, so a minor is a recompile rather
  than a jar swap.

### Fixed

- `Direction` now serializes as Qdrant's lowercase `asc` / `desc`. It was only ever written through the
  hand-rolled query serializer, which spelled it correctly, so no shipped request was affected; the enum
  itself would have sent `ASC` the moment anything else serialized it.

### Internal

- ktlint `12.1.2` → `14.2.0`. Version 14 turns on `class-signature` and `function-signature`, which
  collapse a multi-line parameter list onto one line and push the supertype onto its own; both are
  disabled in `.editorconfig`, for the same reason the codebase picked `intellij_idea` over
  `ktlint_official` in the first place. Two files were rewritten before the rules were turned off.
- detekt's `LongParameterList.functionThreshold` raised from 8 to 12. The `Kdrant(...)` factory is a
  settings surface like `KdrantConfig`, where every parameter past `port` is an independently defaulted
  option, so the two now get the same allowance.

## [1.1.0] - 2026-07-20

### Changed

- **`kdrant-spring-ai` now targets Spring AI `2.0`** (was `1.0`) and **`kdrant-spring-boot-starter` now
  targets Spring Boot `4.1`** (was `3.4`). Kdrant's own public API is unchanged (the `*.api` dumps are
  identical and all adapter tests pass against the new majors), but these two adapter modules now require
  the newer framework generation (Spring Framework 7 / Jakarta EE 11 for the starter). Applications still
  on Spring AI 1.x or Spring Boot 3.x should pin those modules to `1.0.0` until they upgrade. `kdrant-core`
  and `kdrant-transport-rest` are unaffected.
- **`kdrant-langchain4j` now builds against LangChain4j `1.18.0`** (was `1.0.0`) — a backwards-compatible
  minor upgrade.

### Internal

- Toolchain & tooling: Kotlin `2.4.10`, Gradle `9.6.1`, kotest `6.2.2`, plus assorted minor/patch dependency
  bumps; CI actions run on Node 24. The Kotlin modules now compile with `allWarningsAsErrors`, so any
  compiler deprecation fails the build — keeping the code warning-clean across future dependency upgrades.

## [1.0.0] - 2026-07-20

Kdrant's `1.0`: the REST client is feature-complete and its public API is now stable under Semantic
Versioning — see [STABILITY.md](STABILITY.md). On top of `0.2.0` (M10–M18), this release adds M19–M24
(aliases, snapshots, service/analytics endpoints, granular transport & observability, a no-boxing hot
path, quality/CI hardening, the Spring Boot / Spring AI / LangChain4j integrations, and the `catching`
helper).

### Added
- Aliases (M19): `updateAliases { createAlias(collection, alias); deleteAlias(alias); renameAlias(from, to) }`,
  applied by the server as one atomic batch — the primitive behind zero-downtime reindexing (build a new
  collection, then swap the alias in a single step). Plus `listAliases()` and `listCollectionAliases(name)`.
- Service & health endpoints (M19): `healthz()` / `readyz()` / `livez()` (Kubernetes-style probes that return
  a `Boolean` and never throw on a not-ready status), `listCollections()`, `telemetry()` and `listIssues()`
  (raw JSON, since the shape is server-version-specific), `clearIssues()`, and `metrics()` (Prometheus
  text-exposition format).
- Analytics (M19): `facet(name, key, limit, exact) { filter }` — distinct payload-value counts (a histogram
  over a key) — and the distance-matrix endpoints `searchMatrixPairs(name) { sample; limit; using; filter }`
  and `searchMatrixOffsets(...)` (explicit edge-list and sparse-coordinate forms) for clustering/visualization.
- Snapshots & backup/restore (M20): `createSnapshot` / `listSnapshots` / `deleteSnapshot` /
  `recoverSnapshot(location, priority, checksum)` for a collection, plus `createStorageSnapshot` /
  `listStorageSnapshots` / `deleteStorageSnapshot` for the whole storage. Binary transfer is streamed, so a
  multi-GB backup is never buffered in memory: `downloadSnapshot(...)` / `downloadStorageSnapshot(...)` return
  a cold `Flow<ByteArray>`, and `uploadSnapshot(name, data: Flow<ByteArray>, ...)` streams a snapshot file back
  as a multipart upload. `SnapshotPriority` (`NO_SYNC` / `SNAPSHOT` / `REPLICA`) sets the source of truth when
  recovering into a replicated collection. Note: unlike the mutation `wait` flags, snapshot `wait` defaults to
  `true`, matching the Qdrant server default.
- Granular transport & observability (M21): a `configureClient` escape hatch on the `Kdrant(...)` factory
  (an `HttpClientConfig<*>` hook to install your own plugins — metrics, tracing — tune the CIO engine, or
  override any default); `connectTimeout` / `socketTimeout` on the client config; and optional
  request/response logging via `logLevel = LogLevel.…`, which always redacts the `api-key` header so the
  key never reaches the logs.
- Streaming ingest (M21): `upsert(name, points: Flow<PointStruct>)` and `upsert(name, points: Sequence<PointStruct>)`
  — ingest a large or unbounded source without materializing it all in memory; the engine chunks it by the
  configured batch size (sequential, not atomic across chunks, like the DSL `upsert`).
- Ergonomics (M24): `catching { … }` — a coroutine-safe `runCatching` that returns `Result<T>` but re-throws
  `CancellationException` instead of trapping it. The exception-based API stays the primary style.
- No-boxing hot path (M21): the DSL `vector(f1, f2, …)` / `vector(*floatArray)` (upsert) and `query(f1, f2, …)`
  (search) now keep the values in a `FloatArray` and serialize it directly, avoiding a boxed `Float` per element
  (`VectorData.DenseArray` / `QueryInterface.VectorArray`). Upsert batching is byte-aware: a batch is bounded by
  both the point count and a serialized-size cap (`maxUpsertBytes`, default ~30 MiB), so Qdrant's ~32 MiB REST
  limit is respected even for high-dimensional vectors.

## [0.2.0] - 2026-07-20

### Fixed
- **delete-by-filter data loss**: a delete whose filter clauses were all empty (e.g.
  `delete(c) { must { } }`, or `must { if (cond) … }` where `cond` is false at runtime) is no longer
  sent as a match-all filter that would delete every point in the collection. Empty clause blocks now
  normalize away, and delete-by-filter rejects an all-empty filter before issuing any request.
- `collectionExists` now returns `false` on a `404` instead of throwing, matching its documented contract.
- `KdrantException.CollectionNotFound` now carries the server's error message when the server provides one.

### Security
- The client rejects a configuration that sets an `apiKey` without `useTls`, so an API key is never
  sent over plaintext HTTP.

### Added
- Collection config tuning: `updateCollection { optimizers = …; hnsw = …; quantization = … }` (PATCH),
  and `optimizers` / `quantization` (`QuantizationConfig.Scalar` / `.Binary`) on `createCollection`.
- Payload field indexes (`createPayloadIndex(field, PayloadSchemaType.KEYWORD)` / `deletePayloadIndex`), so
  filtering on a field scales instead of doing a full scan; and payload mutations `setPayload` /
  `overwritePayload` / `deletePayload` / `clearPayload` over a points-or-filter selector.
- Vector mutations: `updateVectors` (write new vectors to existing points, keeping payload) and
  `deleteVectors` (remove named vectors from the selected points).
- Advanced retrieval queries on `search`: `recommend { positive(...); negative(...); strategy = ... }`,
  `discover { target(...); context(...) }`, and `context { pair(...) }`. Examples (`VectorInput`) accept a
  dense/sparse vector or a point id.
- Batch and grouped search: `searchBatch { search { } … }` (several searches in one round-trip, hits per
  search) and `searchGroups(groupBy = …) { }` returning `List<PointGroup>`.
- Sparse & multi-vectors: `VectorData.Sparse` / `MultiDense`, `sparseVector(name) { modifier = Modifier.IDF }`
  and per-vector `multivector` in `createCollection`, and `querySparse(...)` / `queryMulti(...)` — enabling
  true dense+sparse hybrid search combined with M14 fusion. Response decoding now degrades an unknown vector
  shape to `VectorData.Raw` instead of failing the whole response.
- Modern `/points/query` search: a polymorphic `query` (nearest by vector or by point id, `orderBy`,
  `sample`), nestable `prefetch { }` sub-requests, and hybrid-search fusion (`rrf(k, weights)` / `dbsf()`),
  plus `lookupFrom` for cross-collection id lookups. The previous `query(vector)` call is unchanged.
- Typed payload access: `kdrantJson` (public default `Json`), `ScoredPoint.payloadAs<T>()` /
  `Record.payloadAs<T>()`, and `QdrantClient.searchAs<T>(): List<Hit<T>>` to decode hit payloads
  straight into your own types.
- Collection conveniences: `getCollectionOrNull`, race-tolerant `createCollectionIfNotExists(...): Boolean`,
  and a `createCollection(name, size, distance = COSINE)` shorthand.
- `PayloadBuilder` index-assignment sugar: `payload["key"] = value` (`operator set`, accepts `null`).
- Automatic retries with exponential backoff + jitter for transient failures (HTTP 429/502/503/504 and
  transient I/O errors), honoring the server's `Retry-After` header. Tunable via `maxRetries`,
  `retryBaseDelay`, and `retryMaxDelay` on the client config (`maxRetries = 0` disables retries).
- Finer error taxonomy: `KdrantException.RateLimited` (429, carrying `Retry-After`), `ServiceUnavailable`
  (503), `ServerError` (other 5xx), and `AlreadyExists` (409).
- Client-side validation of collection parameters: vector `size`, `shardNumber`, and `replicationFactor`
  must be positive, with error messages that echo the received value.
- `kdrant-bom` — a Bill of Materials module to keep `kdrant-core` and `kdrant-transport-rest` on one
  aligned version.

### Changed
- Server errors (HTTP 5xx other than 503) now surface as `KdrantException.ServerError` instead of
  `Transport`, which is now reserved for connection-level I/O failures. HTTP `408` maps to `Timeout`
  and `409` to `AlreadyExists`.
- `local.properties` is no longer tracked in version control.

## [0.1.0] - 2026-07-10

### Added
- Coroutine-first `QdrantClient` with a pluggable `QdrantTransport` seam and a default REST/Ktor
  engine.
- Collection operations: `createCollection` (DSL) and `deleteCollection`.
- `upsert` DSL supporting dense and named vectors, heterogeneous payloads, and automatic batching
  under the REST request-size limit.
- `search` (nearest-vector query over the unified query API) with a DSL for the query vector,
  filter, limit, payload projection, and search params.
- `scroll` exposed as a cold `Flow<Record>` that transparently follows the pagination cursor.
- `delete` by point ids or by filter.
- Collection introspection: `collectionExists` and `getCollection` (status and point counts).
- `count` (optionally filtered) and `retrieve` points by id.
- Complete filter DSL: `must` / `should` / `mustNot` / `minShould` with every Qdrant condition type
  (match/any/except/text, numeric and datetime ranges, `values_count`, geo box/radius/polygon,
  `is_empty` / `is_null`, `has_id`, `has_vector`, per-element `nested`, and recursive sub-filters).
- Typed error hierarchy `KdrantException`.

[Unreleased]: https://github.com/NaCode-Studios/Kdrant/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/NaCode-Studios/Kdrant/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/NaCode-Studios/Kdrant/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/NaCode-Studios/Kdrant/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/NaCode-Studios/Kdrant/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NaCode-Studios/Kdrant/releases/tag/v0.1.0
