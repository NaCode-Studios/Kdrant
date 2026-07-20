# Kdrant

**An idiomatic, coroutine-first Kotlin client for the [Qdrant](https://qdrant.tech) vector database.**

[![CI](https://github.com/NaCode-Studios/Kdrant/actions/workflows/ci.yml/badge.svg)](https://github.com/NaCode-Studios/Kdrant/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nacode-studios/kdrant-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nacode-studios/kdrant-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![API docs](https://img.shields.io/badge/API%20docs-Dokka-blue.svg)](https://nacode-studios.github.io/Kdrant/)

Qdrant's official JVM client is built for Java: every call returns a `ListenableFuture`, requests
are assembled with protobuf builders, and it pulls a large gRPC/Netty stack onto your classpath.
Kdrant is the client you'd actually want to write Kotlin against — `suspend` functions, a type-safe
DSL, `kotlinx-serialization` models, and a small, coroutine-native footprint.

```kotlin
val qdrant = Kdrant(host = "localhost", port = 6333) {
    apiKey = System.getenv("QDRANT_API_KEY")
    requestTimeout = 5.seconds
}

qdrant.use { client ->
    client.createCollection("articles") {
        vector { size = 1_536; distance = Distance.COSINE }
    }

    client.upsert("articles", wait = true) {
        point(id = 1) {
            vector(embedding)
            payload("title" to "Introduction", "lang" to "en", "year" to 2026)
        }
    }
}
```

Kdrant stores and searches vectors you already have — `embedding` above is a `List<Float>` from
your own embedding model; Kdrant does not generate embeddings.

> **Status — 0.2, pre-1.0.** Collections (create/update/delete), `upsert`, the modern `/points/query`
> search (nearest, hybrid fusion, recommend/discover/context, batch, groups), sparse & multi-vectors,
> `scroll`, payload & vector management, resilient retries, and the full filter DSL are implemented and
> tested. APIs may still change before `1.0`.

## Why Kdrant

- **Coroutine-first** — every operation is a `suspend` function; cancellation and timeouts are
  cooperative, and `CancellationException` is always propagated.
- **Type-safe DSLs** — build collections, points, payloads, and filters declaratively, with
  scope-isolated builders (`@DslMarker`), instead of verbose request objects.
- **Small footprint** — a pure-Kotlin REST engine on Ktor + kotlinx-serialization; no gRPC, Netty,
  or protobuf.
- **Typed errors** — failures surface as a sealed `KdrantException` you can exhaustively handle.
- **Pluggable transport** — the wire protocol sits behind a `QdrantTransport` seam, keeping the
  public API independent of it.

### Footprint vs the official client

Dependency stacks verified against `io.qdrant:client:1.18.3`:

| | Kdrant (`kdrant-transport-rest`) | Official `io.qdrant:client` |
| --- | --- | --- |
| Wire protocol | REST/HTTP over Ktor CIO | gRPC (HTTP/2) |
| Heavy dependencies | none — pure Kotlin (Ktor + kotlinx) | `grpc-netty-shaded` (bundled Netty), `grpc-protobuf`/`grpc-stub`, `protobuf-java`, Guava, slf4j |
| Approx. added footprint | ~3–5 MB (Ktor + kotlinx-serialization; coroutines/stdlib are usually already present) | ~15–20 MB of transitive jars (shaded Netty ≈ 9 MB alone) |
| API style | `suspend` functions + `Flow`, type-safe DSL | `ListenableFuture<T>` (Guava), protobuf builders |
| Models | `kotlinx-serialization` data classes | generated protobuf messages |
| GraalVM native / cold start | friendly (no Netty/protobuf reflection config) | needs gRPC/Netty/protobuf native config; heavier cold start |

For raw throughput and streaming, gRPC/HTTP2 still wins — reach for the official client when that is
your bottleneck. For typical RAG and embedding-search workloads, Kdrant trades that for a fraction of
the footprint and idiomatic Kotlin.

## Installation

Requires **JDK 17+**. Artifacts are published to Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kdrant-transport-rest:0.2.0")
}
```

`kdrant-transport-rest` brings in `kdrant-core` transitively; it is the only dependency you add.

You also need a running Qdrant. For local development:

```bash
docker run -p 6333:6333 qdrant/qdrant
```

## Usage

### Connecting

```kotlin
val qdrant: QdrantClient = Kdrant(host = "localhost", port = 6333) {
    apiKey = "..."          // sent as the api-key header; omit for a local, unauthenticated node
    useTls = true           // required in production when sending an API key
    requestTimeout = 10.seconds
}
```

`QdrantClient` is `AutoCloseable`; use it with `use { }` or close it explicitly.

### Collections

```kotlin
// Single (anonymous) vector
qdrant.createCollection("articles") {
    vector { size = 1_536; distance = Distance.COSINE }
    onDiskPayload = true
}

// Named vectors
qdrant.createCollection("multimodal") {
    namedVector("text") { size = 768; distance = Distance.COSINE }
    namedVector("image") { size = 512; distance = Distance.DOT }
}

qdrant.deleteCollection("articles")
```

Create-if-absent (race-tolerant), plus a size+distance shorthand for the common case and a
non-throwing read:

```kotlin
// Returns true if created, false if it already existed (tolerates a concurrent create).
qdrant.createCollectionIfNotExists("articles") { vector { size = 1_536; distance = Distance.COSINE } }
qdrant.createCollection("quickstart", size = 1_536)      // single vector, COSINE

val info = qdrant.getCollectionOrNull("articles")        // null instead of throwing if absent
```

### Upserting points

Point ids are unsigned integers or UUID strings. Payloads accept heterogeneous JSON values.

```kotlin
qdrant.upsert("articles", wait = true) {
    point(id = 1) {
        vector(0.12f, 0.87f, 0.03f /* ... */)
        payload("title" to "Intro", "tags" to listOf("nlp", "kotlin"))
    }
    point(id = "550e8400-e29b-41d4-a716-446655440000") {
        vector("text" to textEmbedding, "image" to imageEmbedding)
        payload {
            put("title", "Cover")
            put("score", 0.91)
        }
    }
}
```

Large batches are split automatically to stay under Qdrant's request-size limit; tune it with
`Kdrant(host, port, upsertBatchSize = 500)`.

### Filters

The filter DSL mirrors Qdrant's filtering model — `must` / `should` / `mustNot` / `minShould`,
every condition type, and recursive nesting — and powers both `search` and delete-by-filter:

```kotlin
val query = filter {
    must {
        "lang" eq "en"
        "year" gte 2024
        "price" between 10.0..99.0
    }
    should {
        matchAny("tag", "featured", "promo")
        geoRadius("location", GeoPoint(lon = 13.40, lat = 52.52), radius = 5_000.0)
    }
    mustNot { "archived" eq true }
}
```

Supported conditions include exact/any/except and full-text match, numeric and datetime ranges,
`values_count`, geo bounding-box / radius / polygon, `is_empty` / `is_null`, `has_id`,
`has_vector`, per-element `nested` filters, and recursive `filter { }` sub-groups.

### Searching

```kotlin
val hits: List<ScoredPoint> = qdrant.search("articles") {
    query(queryVector)
    limit = 5
    scoreThreshold = 0.75
    withPayload = WithPayload.include("title")
    filter { must { "lang" eq "en" } }
}
```

Decode each hit's payload straight into your own type with `searchAs` (or `payloadAs` on a single hit):

```kotlin
@Serializable data class Article(val title: String, val lang: String)

val articles: List<Hit<Article>> = qdrant.searchAs<Article>("articles") {
    query(queryVector); limit = 5
}
val first: Article? = articles.firstOrNull()?.payload
```

**Hybrid search** fuses several `prefetch` sources with Reciprocal Rank Fusion or DBSF:

```kotlin
val hits = qdrant.search("articles") {
    prefetch { query(titleVector); using = "title"; limit = 50 }
    prefetch { query(bodyVector); using = "body"; limit = 50 }
    rrf()            // Reciprocal Rank Fusion; or dbsf()
    limit = 10
}
```

You can also query by a stored point's vector (`query(PointId.num(1))`), `orderBy("field")`, or
`sample()`. Sparse vectors (`querySparse(indices, values)`) and multi-vectors (`queryMulti(...)`) are
supported too — combine a dense and a sparse prefetch under `rrf()` for true dense+keyword hybrid search,
after declaring them with `namedVector(...)` and `sparseVector("keywords") { modifier = Modifier.IDF }`.

### Scrolling

`scroll` returns a cold `Flow` that transparently pages through the collection:

```kotlin
qdrant.scroll("articles", pageSize = 256) {
    filter { must { "lang" eq "en" } }
}.collect { record -> /* ... */ }
```

### Deleting

```kotlin
qdrant.delete("articles", ids = listOf(PointId.num(1), PointId.uuid("...")))
qdrant.delete("articles") { must { "lang" eq "en" } }   // by filter
```

### Counting & retrieving

```kotlin
val total = qdrant.count("articles")
val english = qdrant.count("articles") { must { "lang" eq "en" } }

val points: List<Record> = qdrant.retrieve("articles", ids = listOf(PointId.num(1), PointId.num(2)))
```

### Error handling

```kotlin
try {
    qdrant.upsert("articles") { /* ... */ }
} catch (e: KdrantException.CollectionNotFound) {
    // the collection does not exist
} catch (e: KdrantException.Unauthorized) {
    // missing or wrong API key
}
```

## Architecture

Two modules keep protocol concerns out of the public API:

| Module | Contents |
| --- | --- |
| `kdrant-core` | Public API (`QdrantClient`), models, DSLs, error hierarchy, and the `QdrantTransport` seam — no wire-protocol knowledge. |
| `kdrant-transport-rest` | The default REST engine (Ktor CIO) implementing `QdrantTransport`, plus the `Kdrant(...)` factory. |

The DSLs and client logic live in `kdrant-core` and are independent of the protocol; only the
engine module knows about HTTP.

## Roadmap

**Shipped (`0.1.0`)** — connect; collection management and introspection (`collectionExists` /
`getCollection`); `upsert` (with auto-batching); `search` (over Qdrant's unified query API);
`scroll` as a `Flow`; `count`; `retrieve` by id; `delete` by ids or filter; and the complete filter DSL.

**Next** — a correctness & security patch (`0.1.1`), then retries and typed-payload ergonomics;
the full `/points/query` engine (prefetch, hybrid + RRF/DBSF fusion, sparse & multivectors,
recommend / discovery / grouping); payload indexing and data mutations; aliases and snapshots;
framework integrations (Spring AI / LangChain4j / Koog); and the road to `1.0` — with Kotlin
Multiplatform and an optional gRPC engine after that.

See **[ROADMAP.md](ROADMAP.md)** for the full milestone plan (`M10`–`M25`).

## Building and testing

```bash
./gradlew build         # compile, run unit tests, verify public API (binary-compatibility-validator)
./gradlew apiCheck      # check the tracked public API in *.api
./gradlew apiDump       # regenerate *.api after an intentional public-API change
```

Unit tests need no external services. Integration tests spin up a real Qdrant with
[Testcontainers](https://testcontainers.com) and are skipped automatically when Docker is
unavailable.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Please run `./gradlew build`
before opening a pull request; if you change the public API, run `./gradlew apiDump` and commit the
updated `*.api` files.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Sponsor

If Kdrant is useful to you, consider [sponsoring NaCode Studios](https://github.com/sponsors/NaCode-Studios).
