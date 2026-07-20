# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/NaCode-Studios/Kdrant/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/NaCode-Studios/Kdrant/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/NaCode-Studios/Kdrant/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/NaCode-Studios/Kdrant/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NaCode-Studios/Kdrant/releases/tag/v0.1.0
