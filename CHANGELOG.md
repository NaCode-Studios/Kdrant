# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- Advanced retrieval queries on `search`: `recommend { positive(...); negative(...); strategy = ... }`,
  `discover { target(...); context(...) }`, and `context { pair(...) }`. Examples (`VectorInput`) accept a
  dense/sparse vector or a point id.
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

[Unreleased]: https://github.com/NaCode-Studios/Kdrant/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/NaCode-Studios/Kdrant/releases/tag/v0.1.0
