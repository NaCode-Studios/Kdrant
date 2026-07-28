# Kdrant Roadmap

This document tracks where Kdrant is going. It complements the [CHANGELOG](CHANGELOG.md)
(which records what has already shipped) and the short *Roadmap* section in the
[README](README.md). How milestones and shipped-state are marked here follows the shared
[roadmap conventions](ROADMAP-CONVENTIONS.md) — the same standard Kmemo uses.

As of `1.0`, Kdrant's public API is stable under SemVer (see [STABILITY.md](STABILITY.md)); the post-`1.0`
milestones below are additive and may be re-ordered as the project learns. Every public-API change is
tracked by the binary-compatibility-validator (`*.api` files), so breakage is never silent.

## Guiding principles

- **Coroutine-first and idiomatic.** Every operation is a `suspend` function or a `Flow`;
  cancellation is cooperative and `CancellationException` is always propagated. New surface area
  follows the same type-safe, scope-isolated DSL style rather than exposing raw request objects.
- **Small footprint, REST by default.** The default engine stays a pure-Kotlin Ktor + `kotlinx-serialization`
  stack — no gRPC, Netty, or protobuf on the classpath unless you opt in.
- **Pluggable transport seam.** All wire-protocol work sits behind `QdrantTransport`. A gRPC engine
  is an *opt-in* module, never a requirement, and the REST engine remains the recommended default.
- **Positioning rule.** Compete on developer experience, footprint, cold-start / GraalVM
  friendliness, and Kotlin idioms — not on raw gRPC throughput. Benchmarks stay honest about the
  cases where the official gRPC client is the better choice.

## Status — `1.1.0` (current)

`1.1.0` is a maintenance & compatibility release on top of `1.0.0`. The Spring adapters move to the current
framework generation — **`kdrant-spring-ai` targets Spring AI `2.0`** and **`kdrant-spring-boot-starter`
targets Spring Boot `4.1`** (applications on Spring AI 1.x / Spring Boot 3.x should pin those modules to
`1.0.0`); **`kdrant-langchain4j`** builds against LangChain4j `1.18`. The toolchain moves to Kotlin `2.4.10`
and Gradle `9.6.1`, and the Kotlin modules now compile with `allWarningsAsErrors` so deprecations can't slip
in. Kdrant's own public API is unchanged.

## Status — `1.0.0`

`1.0.0` marks a **stable public API** under SemVer (see [STABILITY.md](STABILITY.md)). On top of `0.2.0`
it adds aliases and snapshots (M19–M20); observability & a granular transport seam with a no-boxing hot
path and streaming ingest (M21); quality & CI hardening — ktlint/detekt, a JDK & Qdrant-version matrix,
Dependabot, property-based tests (M22); the **Spring Boot / Spring AI / LangChain4j** integrations and a
runnable RAG example (M23); and the `catching` helper, this stability policy, and a JMH benchmark harness
(M24). Koog is scoped as an upstream contribution.

## Status — `0.2.0`

`0.2.0` is a large feature release on top of `0.1.0`'s core (collections, `upsert`, `delete`, `count`,
`retrieve`, `scroll` as a `Flow`, and the full filter DSL). It adds:

- **Correctness & security (M10):** the delete-by-filter data-loss fix, TLS enforced when an API key is
  set, `404` message/`exists` handling, and client-side parameter validation.
- **Resilience (M11):** retries with exponential backoff + jitter honoring `Retry-After`, and a finer
  `KdrantException` taxonomy (`RateLimited` / `ServiceUnavailable` / `ServerError` / `AlreadyExists`).
- **Typed DX (M12):** `kdrantJson`, `payloadAs<T>` / `searchAs<T>` / `Hit<T>`, `getCollectionOrNull`,
  `createCollectionIfNotExists`, a `createCollection(name, size, distance)` shorthand, and `PayloadBuilder` sugar.
- **Positioning (M13):** the `kdrant-bom` module, richer POM metadata, a footprint comparison table, and a
  Dokka multi-module docs site on GitHub Pages.
- **Modern search engine (M14–M16):** the polymorphic `/points/query` (`QueryInterface`), nestable
  `prefetch`, RRF/DBSF fusion, sparse & multi-vectors, `recommend` / `discover` / `context`, `searchBatch`,
  and `searchGroups`.
- **Data & collection management (M17–M18):** payload field indexes, payload & vector mutations, and
  `updateCollection` (PATCH) with optimizers / HNSW / quantization.

Published to Maven Central and GitHub Packages.

## Progress

| Milestone | Status |
| --- | --- |
| **M10–M18** | ✅ Shipped in `0.2.0`. |
| **M19** · Aliases, service & analytics endpoints | ✅ Shipped in `1.0.0`. |
| **M20** · Snapshots & backup/restore | ✅ Shipped in `1.0.0`. |
| **M21** · Observability, granular transport, no-boxing hot path | ✅ Shipped in `1.0.0`. |
| **M22** · Quality, supply chain & test depth (CI) | ✅ Shipped in `1.0.0` (some sub-items deferred). |
| **M23** · Ecosystem (Spring / LangChain4j / Koog) + RAG demo | ✅ Shipped in `1.0.0` (Koog = upstream contribution). |
| **M24** · The road to `1.0` | ✅ Shipped in `1.0.0` — Tier 4 complete. |
| **M25** · KMP, optional gRPC, cluster/sharding | Post-`1.0`. |
| **M26** · Drop-in adapters: metadata-filter translation | Planned. |
| **M27** · Deployment ergonomics: `ensureCollection`, ordered `scroll`, `batchUpdate` | Planned. |
| **M28** · Observability batteries included | Planned. |
| **M29** · Contract tests, coverage & provenance | Planned. |
| **M30** · Lowering the switching cost: docs & published numbers | Planned. |

**Next up.** Tier 6 (M26–M30) is sequenced *before* Tier 5's M25: it closes gaps that block adoption
today, where M25 opens a new market. Within it, **M26** and **M29** come first — one makes the
integrations the adapters already advertise actually usable, the other protects every guarantee the
client makes as Qdrant ships minors — then M27, M28 and M30.

**Deferred sub-items — now promoted.** Several items parked since `0.2.0` / `1.0.0` are no longer
deferred; they are the Tier 6 milestones above. Metadata-filter translation for the Spring AI /
LangChain4j adapters (was M23) → **M26**. `ensureCollection` + the enriched `CollectionInfo` read-back
(was M18), `order_by` on `scroll` (was M14) and `batchUpdate` (was M17) → **M27**. The `X-Request-Id`
correlation header and bundled Micrometer hooks (were M21) → **M28**. Contract tests vs the Qdrant
OpenAPI schema, Kover coverage and build provenance (were M22) → **M29**.

**Still deferred:** `Formula` / MMR reranking (M16); parameterized payload-index params such as the text
tokenizer (M17); Product quantization and `wal` / `strictMode` / `params` config on update (M18);
shard-scope snapshots (M20); the Koog backend (M23 — an upstream contribution, pending a published Koog
`rag-vector` artifact). A **`main` snapshot job** (M22) stays **decided against** — releases are
tag-driven, the convention shared with Kmemo.

**Considered and declined:**

- **A `Flow`-based paged `search`.** `search` returns a bounded top-`k` by design, and paging it with
  `offset` makes the server re-run the ANN search for every page — so a `searchFlow(limit = 10_000)`
  would dress up deep pagination as an ergonomic API. `scroll` is already the `Flow` for "read many",
  and M27 gives it the deterministic ordering that use case actually needs.
- **Connection-pool settings on `KdrantConfig`.** Accepted as a capability, declined in that location:
  `maxConnectionsPerRoute` and `keepAliveTime` are CIO concerns, and `KdrantConfig` is transport-neutral
  by decision (M12, where `upsertBatchSize` was kept out for the same reason). They land as named
  parameters of the REST-engine factory instead (M28).
- **A bundled `kdrant-jdk` `CompletableFuture` facade.** The `1.0` position stands (Kotlin-first, bridged
  via `kotlinx-coroutines-jdk8`): a hand-written facade is permanent public API to maintain for a market
  that has not asked yet. Revisit on evidence — issues or dependents from Java-only codebases. Kmemo
  holds the same line for the same reason.
- **Documenting HTTP/2 as the throughput answer.** Ktor CIO's HTTP/2 support is not the settled story
  the suggestion assumes, and the honest reply to "gRPC is faster" is M30's published numbers, not an
  unmeasured protocol claim.
- **ADRs as a separate directory.** The rationale is worth writing down; the ceremony around it is not.
  It goes in [STABILITY.md](STABILITY.md) (M30), the way Kmemo records the reasoning behind its defaults.

The detailed milestone descriptions below are kept as the plan of record; the tiers marked shipped above are already released.

## Effort legend

`S` ≈ hours–1 day · `M` ≈ several days · `L` ≈ 1–2 weeks · `XL` ≈ multi-week / multiple sub-parts.

---

## Tier 0 — Correctness & security patch — ✅ Shipped in `0.2.0`

### M10 · Correctness & security hardening — `S`

Ship the fixes that protect data and credentials before pushing adoption.

- Harden **delete-by-filter** so a filter that ends up with no effective conditions can never be
  sent as a match-all: normalise empty clause lists to `null` in `FilterBuilder.build()` (and/or
  check real non-emptiness at the call site), with regression tests for empty `must {}` /
  `should {}` / `minShould(0) {}` blocks.
- **Enforce TLS when an API key is set** — `require(apiKey == null || useTls)` in `KdrantConfig`
  (the KDoc already states TLS is required when sending a key); optionally default `useTls = true`
  when a key is present.
- Preserve the server's error message on `404` and treat `404` on `/exists` as `false` to match the
  `collectionExists` contract.
- Client-side validation of numeric parameters (vector `size > 0`, `shardNumber`,
  `replicationFactor`) with messages that echo the received value.
- Remove `local.properties` from version control (`git rm --cached` + `.gitignore`).

---

## Tier 1 — Robustness, DX & reach — ✅ Shipped in `0.2.0`

### M11 · Resilience & error taxonomy — `M`

Make the client resilient to transient failures and return actionable, correctly-classified errors.

- Install Ktor `HttpRequestRetry` with exponential backoff + jitter, limited to `429/502/503/504`
  and `IOException`, honouring the `Retry-After` header.
- New `KdrantException` subtypes: `RateLimited(retryAfter)`, `ServiceUnavailable`, `ServerError`,
  `AlreadyExists`; map `429 → RateLimited` and `503 → ServiceUnavailable` **before** the generic
  `4xx → InvalidRequest` catch-all.
- Expose `maxRetries` and backoff parameters in `KdrantConfig`; document which exceptions are retryable.

### M12 · DX front door — typed payloads & collection convenience — `S`

Make Kdrant the API "you'd write by hand", removing boilerplate from the first-run experience.

- A public default `Json` + `payloadAs<T>()` on `ScoredPoint` / `Record`, plus
  `searchAs<T>(): List<Hit<T>>` — the primary RAG pattern, and the headline of the README.
- `getCollectionOrNull` and `createCollectionIfNotExists(...): Boolean` as pure extensions
  (race-tolerant once `KdrantException.AlreadyExists` from M11 lands; otherwise documented as best-effort).
- A `createCollection(name, size, distance = COSINE)` overload for the common case.
- `PayloadBuilder`: `put(List)`, `put(Map)`, `operator fun set(key, value: Any?)`.
- Keep `upsertBatchSize` a REST-engine concern (a named parameter of the `Kdrant(...)` factory), **not**
  in the transport-neutral core config — decided against moving it into `KdrantConfigBuilder` to preserve
  the core's protocol independence.

### M13 · Positioning & discoverability — `M`

Turn the "coroutine-first, no-gRPC" wedge into a quantified, repeatable message and get found where
Kotlin/JVM developers discover libraries.

- A quantified comparison table vs `io.qdrant:client` (transitive deps, classpath MB, cold-start,
  GraalVM, Kotlin idioms) and a single tagline replicated across POM / GitHub about / site.
- Rich POM metadata: `description`, keywords, `url`, `scm`.
- A Dokka multi-module docs site on GitHub Pages (quickstart, filters, RAG, migration guides).
- Community listings: PR to the Qdrant community/clients page, `awesome-kotlin`, Qdrant DevRel contact.
- A `kdrant-bom` (`java-platform`) module published alongside the other artifacts.

---

## Tier 2 — Search engine at parity with Qdrant — ✅ Shipped in `0.2.0`

Kdrant already calls the modern `/points/query` endpoint, but only models its "nearest-dense" shape.
These three milestones open up the full polymorphic query interface — the biggest functional gap.

### M14 · Query API foundations: `/points/query`, prefetch, fusion — `M`

Introduce Qdrant's modern query architecture as the common root; without it, hybrid, recommend, and
discovery are not expressible.

- A `sealed QueryInterface` (`Nearest`, `Fusion`, `ById`, `OrderBy`, `Sample`, …) as the root of the
  `/points/query` body.
- A nestable `prefetch { query(...); using = ...; limit = ... }` block + `prefetch` on `SearchRequest`.
- `Fusion.RRF(k, weights)` / `Fusion.DBSF` combined with multiple prefetch clauses (hybrid search).
- A `query(id: PointId)` overload (reuse a stored vector) and a `lookup_from(collection, vector)` field.
- `OrderBy(key, direction, startFrom)` and `Sample(RANDOM)`; add `order_by` to `scroll` too.

### M15 · Sparse & multivectors, end-to-end — `L`

Support sparse and multi-vector / late-interaction (ColBERT) embeddings in storage, config, and
query — and make the transport tolerant of non-dense shapes.

- `VectorData.Sparse(indices, values)`; `sparse_vectors` config in `createCollection`; a sparse
  query variant in `QueryInterface`.
- Multivector config at creation; `VectorData.MultiDense(List<List<Float>>)`; a multivector (ColBERT) query.
- Extend the REST (de)serializers for sparse / multi / mixed-named vectors, degrading unknown shapes
  gracefully instead of failing the whole response (today only dense / named-dense parse).
- (De)serialization tests for the non-dense shapes.

### M16 · Advanced retrieval: recommend, discovery, grouping, batch, rerank — `L`

Complete Qdrant's advanced retrieval coverage on top of the `QueryInterface` base.

- `RecommendInput(positive, negative, strategy)` + a `recommend { … }` DSL.
- `DiscoverInput(target, context)` and `ContextInput(pairs)` + a dedicated DSL.
- `searchGroups(name) { groupBy = …; groupSize = … }` on `/points/query/groups`.
- `searchBatch(name, List<SearchRequest>): List<List<ScoredPoint>>` on `/points/query/batch`.
- Reranking: a `Formula(expression)` variant with a minimal AST, plus an `mmr` option on nearest queries.

---

## Tier 3 — Complete data & collection management

### M17 · Payload / vector mutations & payload indexes — `M`

**Status: ✅ Shipped in `0.2.0`.**

Make the already-shipped filter DSL genuinely useful (filters don't scale without indexes) and
complete data mutation.

- Payload indexes: `createPayloadIndex(name, field, schema) { … }` + `deletePayloadIndex`; a sealed
  `PayloadFieldSchema` (Keyword / Integer / Float / Bool / Geo / Datetime / Uuid / Text).
- Payload mutations: `setPayload` / `overwritePayload` / `deletePayload` / `clearPayload`, reusing
  `DeleteSelector` (`Ids | ByFilter`) and `PayloadBuilder`, with nested-key support.
- `updateVectors` (`PUT /points/vectors`) and `deleteVectors` (`POST /points/vectors/delete`).
- `batchUpdate(name) { … }` with a sealed `UpdateOperation` (`POST /points/batch`). Note: this is a
  **single ordered request** applying a mixed sequence of point/vector/payload operations — ordered,
  but **not transactional** (no all-or-nothing rollback across operations).

### M18 · Full collection config & richer read-back — `L`

**Status: ✅ Shipped in `0.2.0`.**

Bring `createCollection` / `updateCollection` up to production config and expose in read-back what
the convenience helpers need.

- `updateCollection(name) { optimizers { }; hnsw { }; quantization { }; vectors { … }; params { … }; strictMode { } }`.
- A sealed `QuantizationConfig` (Scalar / Product / Binary), collection- and per-vector level, with
  rescore / oversampling in the search params.
- Extend `createCollection` with `optimizers { }`, `wal { }`, `strictMode { }`; share models between
  create and update. Note: `optimizers` and `strict_mode` config apply to both create and update, but
  `wal` config is **create-only**. (`init_from` is intentionally omitted — it was removed from the
  current Qdrant API.)
- Enrich `CollectionInfo` (today: status / points / indexed / segments) with
  `config.params.vectors` (size / distance), `payload_schema`, and `optimizer_status` — which finally
  unblocks a correctness-checking `ensureCollection`.

### M19 · Aliases, service & analytics endpoints — `M`

**Status: ✅ Shipped in `1.0.0`.** All three groups below shipped, with MockEngine wire tests.

Round out the operational surface: zero-downtime reindex, server-side health, and analytics.

- Aliases: `updateAliases { createAlias / deleteAlias / renameAlias }` + `listAliases()` /
  `listCollectionAliases(name)` (`POST /collections/aliases`) — enables zero-downtime reindex.
- Service endpoints: `healthz()` / `readyz()` / `livez()`, `listCollections()`, and optional
  `telemetry()` / `metrics()` (Prometheus, non-JSON) and `listIssues()`.
- Analytics: `searchMatrix(sample, limit)` (pairs / offsets forms) and `facet(key, filter, limit, exact)`.

### M20 · Snapshots & backup / restore — `L`

**Status: ✅ Shipped in `1.0.0`** for the collection and full-storage scopes. **Shard-scope
snapshots are deferred.**

Provide the backup/restore story enterprise adoption expects, designed separately because of binary bodies.

- `createSnapshot` / `listSnapshots` / `deleteSnapshot` / `recoverSnapshot(location)` for
  collection and full-storage scopes (shard scope deferred).
- Streaming download (`downloadSnapshot` → `Flow<ByteArray>`) / upload (`uploadSnapshot` from a
  `Flow<ByteArray>`, multipart) with binary Ktor bodies, as a dedicated API distinct from the JSON surface.

---

## Tier 4 — Production reliability, ecosystem & the road to `1.0` — ✅ Shipped in `1.0.0`

### M21 · Observability, granular transport & no-boxing hot path — `L`

**Status: ✅ Shipped in `1.0.0`.** Delivered: the `configureClient` client-customization seam,
`connectTimeout` / `socketTimeout`, api-key-redacting logging, `Flow` / `Sequence` upsert, the
`FloatArray` no-boxing dense path (`vector` / `query`), and byte-aware upsert batching (`maxUpsertBytes`).
**Deferred:** the `X-Request-Id` correlation header and bundled Micrometer / OpenTelemetry hooks — both
reachable today through the `configureClient` seam.

Open the transport up to extension and observability, and optimise the hot upsert/search paths and
streaming ingestion.

- A public `HttpClientConfig<*>.() -> Unit` seam (or plugin install hook) in `KdrantConfig` / factory.
- An optional Logging plugin with api-key redaction + correlation headers (`X-Request-Id`), plus
  metrics / tracing hooks (Micrometer / OpenTelemetry).
- Separate `connectTimeout` / `socketTimeout` and CIO tuning
  (`maxConnectionsPerRoute`, `keepAliveTime`, …).
- A `FloatArray`-backed dense representation with a dedicated serializer (zero boxing on the hot path)
  + `FloatArray` overloads on `vector` / `query`.
- Upsert from `Flow<PointStruct>` / `Sequence` (reusing the chunking) and **byte-aware adaptive
  batching** so the documented 32 MiB REST cap is actually respected (today batching is by point count).

### M22 · Quality, supply chain & test depth (CI) — `M`

**Status: ✅ Shipped in `1.0.0`** (some sub-items deferred). Delivered: **ktlint** + **detekt** as `check` gates
(configured to the codebase's style); a JDK `17` / `21` build matrix and a Qdrant-version matrix
(pinned + `latest`) via Testcontainers; **Gradle wrapper-validation**; a **dependency-review** step on
PRs; **Dependabot** (Gradle + GitHub Actions, grouped); and **property-based round-trip** tests on the
`@Serializable` models (kotest-property). **Deferred:** **Kover** coverage (0.9.1 is not yet compatible
with the Kotlin 2.4 Gradle plugin); build-provenance / SLSA attestation + a `main` snapshot job; and
contract tests vs the Qdrant OpenAPI schema.

Bring CI and tests up to a mature OSS standard and catch wire-format regressions across Qdrant versions.

- Kover (report + minimum threshold, badge) + detekt + ktlint as Gradle tasks and CI gates.
- Dependabot / Renovate (Gradle + GitHub Actions) + a dependency-review / CVE step on PRs.
- A JDK `17 / 21 (/ 23)` matrix and a Qdrant-version matrix (latest minor + previous) via Testcontainers.
- `gradle/actions/wrapper-validation`; build-provenance / SLSA attestation on release; a snapshot job on `main`.
- Property-based round-trip encode/decode tests on the `@Serializable` models + contract tests vs the
  Qdrant OpenAPI schema.

### M23 · Ecosystem: Spring Boot / Spring AI / LangChain4j / Koog & a RAG app — `XL`

**Status: ✅ Shipped in `1.0.0`** (Koog = upstream contribution). Delivered: the **`kdrant-spring-boot-starter`** (`@ConfigurationProperties("kdrant")`
+ `@AutoConfiguration` exposing a `destroyMethod = "close"`, `@ConditionalOnMissingBean` `QdrantClient`
bean; `ApplicationContextRunner` test) and **`kdrant-spring-ai`** (a Spring AI `VectorStore` backed by
Kdrant — `add` / `delete` / `similaritySearch`, embedding via a Spring AI `EmbeddingModel`; metadata-filter
expressions are not yet translated) and **`kdrant-langchain4j`** (a LangChain4j `EmbeddingStore<TextSegment>`
— `add` / `addAll` / `search`; metadata filters not yet translated), and the runnable **`example-rag`** app
(Ktor + Kdrant, ingest → embed → retrieve, `docker-compose` for Qdrant).

**Koog** stays an **upstream contribution** (as originally scoped). Its RAG storage SPI —
`ai.koog.rag.vector.backend.VectorStorageBackend<Document>` (coroutine-first: `store` / `delete` / `read` /
`readWithPayload` / `allDocuments` + ranking) — is a natural fit for a Kdrant-backed impl, but it lives in
Koog's own `rag-vector` module, which is not published to Maven Central at a resolvable coordinate. The
integration therefore belongs in the Koog repository (or awaits a published Koog `rag-vector` artifact),
rather than as a `kdrant-koog` module here.

Meet JVM developers inside the ecosystems they already use, and publish the single highest-leverage
adoption driver: a runnable RAG demo. (Depends on query/collection completeness — M14–M18.)

- `kdrant-spring-boot-starter`: `@ConfigurationProperties("kdrant")` + auto-config exposing a
  `QdrantClient` bean (`destroyMethod = "close"`, `@ConditionalOnMissingBean`).
- `kdrant-koog`: a `VectorStorage` / `EmbeddingBasedDocumentStorage` backend for JetBrains Koog
  (contributed upstream).
- `kdrant-spring-ai` (`VectorStore`) and `kdrant-langchain4j` (`EmbeddingStore`): a one-line-dependency
  migration that swaps the gRPC stack for the REST transport.
- `examples/`: a runnable RAG app (Ktor + an embedding provider + Kdrant, docker-compose for Qdrant),
  linked at the top of the README.
- A coordinated launch with Qdrant DevRel (blog + Kotlin Weekly / r/Kotlin / Slack).

### M24 · The road to `1.0`: stability, benchmarks, final ergonomics — `M`

**Status: ✅ Shipped in `1.0.0` — this completes Tier 4.** Delivered: a written semver/stability
policy + `1.0` scope-and-date plan ([STABILITY.md](STABILITY.md)); a JMH latency harness for upsert/search
([`benchmarks/`](benchmarks/)); the `catching { }` helper (coroutine-safe `Result<T>`); and the documented
Java-interop decision (Kotlin-first; bridge via `kotlinx-coroutines-jdk8`; no bundled facade in `1.0`).
The reproducible benchmark *numbers* and the `1.0` tag itself land when the `0.x` line is released and baked.

Cut `1.0` with written stability guarantees and back the footprint claims with reproducible numbers.

- A written semver / stability policy + a `1.0` scope-and-date plan; cut `1.0` once the REST core is
  stable (snapshots + aliases done) — without waiting for gRPC.
- A JMH benchmark repo (p50/p99 upsert/search latency) + a footprint table vs `io.qdrant:client`,
  repeatable in CI; honest about where gRPC wins.
- A `catching { }` helper returning `Result<T>` (re-throwing `CancellationException`); the
  exception-based style stays primary.
- Decide and document the Java-interop position; an optional `kdrant-jdk` facade
  (`CompletableFuture`) if warranted.

---

## Tier 5 — Post-`1.0`

### M25 · KMP, optional gRPC, cluster / sharding — `L`

Expand the market after `1.0` without delaying its time-to-market. (Sequenced after Tier 6: this opens a
new market, where Tier 6 closes gaps in the one Kdrant already serves.)

- Convert `kdrant-core` to Kotlin Multiplatform `commonMain`. The core is already ~80% there, and what
  remains is a short, concrete list rather than a rewrite:
  - `java.util.UUID` → `kotlin.uuid.Uuid`, or an `expect` / `actual` id if that API is still
    experimental on the pinned Kotlin at the time.
  - `java.time.Instant` → `kotlinx-datetime` / `kotlin.time`.
  - the hard-coded `Dispatchers.IO` → a `CoroutineDispatcher` on `KdrantConfig`, defaulting to
    `Dispatchers.IO` on JVM and `Dispatchers.Default` elsewhere.
  - the Ktor engine parameterised per target (CIO on JVM, Darwin on Apple platforms, Js, Curl on Native).
- Publish KMP targets of the REST engine; announce on klibs.io / kmp-awesome. There is no multiplatform
  Qdrant client today, which makes this a market rather than a feature. **Kmemo's M17 is the same
  migration on the same toolchain** — run them together and pay the learning once.
- `kdrant-transport-grpc` as a separate, opt-in module (REST stays the recommended default);
  document the footprint-vs-throughput/streaming trade-off.
- Cluster / sharding: `collectionClusterInfo(name)` + `updateCollectionCluster(name) { … }` and
  `createShardKey` / `deleteShardKey`.

---

## Tier 6 — From "a good Kotlin client" to "the default choice"

The near-term work after `1.1`. Every milestone here closes a gap between what Kdrant *offers* and what
an adopter can actually *use in production*: adapters that advertise an integration they do not complete,
workflows (GitOps bootstrap, resumable ETL) with no idiomatic answer, observability that exists only as a
seam, a wire contract nothing verifies, and capabilities that ship but are documented nowhere as patterns.

### M26 · Drop-in adapters: metadata-filter translation — `M`

`kdrant-spring-ai` and `kdrant-langchain4j` accept the frameworks' filter types but do not translate
them. Filtered retrieval is the common RAG case, not an edge case, so today an application that filters
cannot swap its backend for Kdrant without rewriting its retrieval layer. This is the whole distance
between "an adapter exists" and "a drop-in replacement", and it is the largest single gap in the
integration story.

- A `FilterTranslator` seam per adapter: Spring AI's `Filter.Expression` AST → the Kdrant filter DSL,
  and LangChain4j's `Filter` → the same DSL, sharing whatever is genuinely common rather than forcing it.
- Cover the operators the frameworks actually define (`EQ` / `NE` / `GT` / `GTE` / `LT` / `LTE` / `IN` /
  `NIN`, `AND` / `OR` / `NOT`), mapping each onto `must` / `should` / `mustNot` with `match` / `range`.
- **Fail loudly on an expression that cannot be translated**, never silently drop the clause — a dropped
  filter returns confidently wrong documents, and a wrong result is far more expensive than an error.
- Payload-key mapping (framework metadata key → Qdrant payload key), plus the payload-index guidance
  (M17) that keeps those filters fast once they work.
- Per-operator round-trip tests against a real Qdrant via Testcontainers, driven through each framework's
  own filter-expression parser so the input is the real AST, not a hand-built one.

### M27 · Deployment ergonomics: idempotent bootstrap & deterministic reads — `M`

Three items carried forward from `0.2.0`, each of which blocks a concrete production workflow.

- **`ensureCollection(name) { … }`** — the idempotent `exists` + `create` + reconcile-config combination,
  returning a sealed `EnsureResult` (`Created` / `Updated` / `Unchanged`) so a GitOps or CI/CD bootstrap
  script is rerunnable *and* its outcome is assertable. The enriched `CollectionInfo` read-back this
  needed shipped in `0.2.0` (M18), so the blocker is already gone. Reconciliation only touches what
  `updateCollection` can legally change; a mismatch it cannot fix (a different vector size or distance)
  is an error, never a silent recreate that drops data.
- **`order_by` on `scroll`** (carried from M14, where `search` got it). Without a deterministic order a
  `scroll`-backed ETL or reindex job cannot resume where it stopped — which is most of the reason to
  reach for `scroll` in the first place.
- **`batchUpdate(name) { … }`** (carried from M17) — a single ordered request applying a mixed sequence
  of point / vector / payload operations. Ordered, and documented as explicitly **not** transactional.

### M28 · Observability batteries included — `M`

`configureClient` makes all of this *reachable*; a team standing up a service should not have to build it
first. `kmemo-micrometer` already proves the shape on the sibling project.

- **`X-Request-Id`** generated client-side per request and attached to every call, and surfaced on
  `KdrantException` so a failure in a log can be traced back to the request that caused it.
- **`kdrant-micrometer`**, an opt-in `MeterBinder` module (never on the core classpath) exposing request
  duration, retry counts, per-status-class errors and upserted-point counts — with cardinality bounded
  deliberately: collection name yes, point ids never.
- **Structured logging context** — collection, operation and point count attached to the existing
  api-key-redacting log seam, which is what makes a high-traffic log searchable.
- **CIO pool tuning** (`maxConnectionsPerRoute`, `keepAliveTime`) as named parameters of the REST-engine
  factory — deliberately *not* on `KdrantConfig`, which stays transport-neutral (see the declined note
  above).

### M29 · Contract tests against the Qdrant OpenAPI schema — `M`

The highest-value item left in M22, and the one that protects every other guarantee: Qdrant ships minors
quickly, and a renamed or newly-required field currently becomes a runtime failure in a user's service
rather than a build failure here.

- Validate the `@Serializable` request / response models against Qdrant's published OpenAPI schema, run
  inside the existing Qdrant-version CI matrix (pinned + `latest`) so `latest` drifting is what breaks
  first — in CI, with a version number attached.
- Treat an unknown *response* field as tolerable (the deserializers already degrade gracefully by
  design), but a missing or renamed *required* field as a failure.
- **Kover.** The recorded blocker names Kover `0.9.1`, but the version catalog already pins `0.9.9` with
  the plugin declared and never applied — re-verify against the current Kotlin `2.4.10` / Gradle `9.6.1`
  toolchain before assuming it is still blocked. If it resolves, a coverage report, a threshold and a
  badge follow; if not, the reason gets re-recorded with the version actually tested.
- **Build provenance:** GitHub's `attest-build-provenance` on the release workflow — a few lines, and the
  artifact enterprises with strict supply-chain policies ask for before they will take a dependency.

### M30 · Lowering the switching cost: migration guide, cookbook & published numbers — `M`

Everything below is already built; none of it is documented as a *pattern*, and the benchmark harness
produces no public numbers. This is the cheapest adoption work left on the board.

- A **migration guide from `io.qdrant:client`** — side-by-side before/after for the dozen most common
  operations, the dependency-stack delta, and an honest section on where the official gRPC client is
  still the better choice. (Scoped in M13 as part of the Dokka site; never written.)
- A **cookbook** of patterns that are answerable today with shipped features: RAG with Spring AI,
  zero-downtime reindex with aliases (M19), bulk import over `Flow` upsert with byte-aware batching
  (M21), and a backup/restore strategy with snapshots (M20).
- **Design decisions written down** — REST over gRPC by default, `FloatArray` over boxed vectors, DSLs
  over data classes, no Java facade — as the rationale section of [STABILITY.md](STABILITY.md).
- **Published benchmark numbers.** The footprint / dependency-count / cold-start table is deterministic
  and belongs in CI on every PR. The JMH latency figures are run and published on a schedule instead —
  shared CI runners are too noisy to gate microbenchmarks per-PR, and a flaky gate only teaches people to
  ignore it. Both stay honest about where gRPC wins.
- The community listings still open from M13: `awesome-kotlin`, and the Qdrant community-clients page.
