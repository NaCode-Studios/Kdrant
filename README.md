<p align="center">
  <img src="docs/kdrant-hero.png" alt="Kdrant, a coroutine-first Kotlin client for the Qdrant vector database" width="100%">
</p>

# Kdrant

**An idiomatic, coroutine-first Kotlin client for the [Qdrant](https://qdrant.tech) vector database.**

[![CI](https://github.com/NaCode-Studios/Kdrant/actions/workflows/ci.yml/badge.svg)](https://github.com/NaCode-Studios/Kdrant/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nacode-studios/kdrant-core?label=Maven%20Central&labelColor=0B0E17&color=7F52FF)](https://central.sonatype.com/artifact/io.github.nacode-studios/kdrant-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-232B45?labelColor=0B0E17)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white&labelColor=0B0E17)](https://kotlinlang.org)
[![API docs](https://img.shields.io/badge/API%20docs-Dokka-232B45?labelColor=0B0E17)](https://nacode-studios.github.io/Kdrant/)
[![Website](https://img.shields.io/badge/website-nacodestudios.it-232B45?labelColor=0B0E17)](https://nacodestudios.it/en/project/kdrant)

Qdrant's official JVM client is built for Java: every call returns a `ListenableFuture`, requests are
assembled with protobuf builders, and the wire is decided for you: gRPC, with a shaded Netty on your
classpath whether you needed the throughput or not. Kdrant is the client you would want to write Kotlin
against: `suspend` functions, a type-safe DSL, `kotlinx-serialization` models, and a wire you pick. The
default engine is pure-Kotlin REST and pulls in no gRPC, no protobuf and no Netty; the gRPC engine is
one dependency away when throughput is the bottleneck. Both are the same `QdrantClient` and are held to
the same behavioural test suite against a real Qdrant.

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

**Kdrant computes no embeddings.** It bundles no model and depends on no inference library, so
`embedding` above is a `List<Float>` you produced. Where your Qdrant has an inference provider
configured, Kdrant can send the text and the model name and let the server produce the vector: see
[server-side inference](#server-side-inference).

> **See it end to end.** [`example-rag`](example-rag/) is a small runnable Retrieval-Augmented-Generation
> service (ingest, embed, store, retrieve) built on Kdrant, with a `docker-compose` for Qdrant.

> **Status — `2.2`, stable.** Both engines cover Qdrant's API: collections, points, the modern
> `/points/query` search including hybrid fusion, sparse and multi-vectors, scroll, payload and vector
> management, aliases, snapshots, cluster and sharding. The public API is stable under SemVer and
> tracked per target; see [STABILITY.md](STABILITY.md). The plan is on the
> [board](https://github.com/orgs/NaCode-Studios/projects/4).

## Why Kdrant

**Every operation is a `suspend` function or a `Flow`.** Cancellation and timeouts are cooperative and
`CancellationException` always propagates, so a request abandoned by a caller is a request that stops.

**The default engine costs no gRPC stack.** REST on Ktor and kotlinx-serialization adds roughly 3 to 5 MB;
protobuf, Netty and grpc-java reach your classpath only if you ask for `kdrant-transport-grpc` by name,
and a build that does not ask is checked on every commit rather than trusted.

**Failures are a sealed hierarchy you can handle exhaustively**, identically over either engine, and
every one of them says whether the same request could succeed later. A degraded cluster reports itself
as `ReadOnly`, `ShardUnavailable` or `PartiallyApplied` rather than as a generic failure.

**The wire sits behind one seam.** That is not a claim about layering: it is why a second engine exists
without a line changing in `kdrant-core`, why tracing and metrics are one decorator each rather than one
per engine, and why the same code sends a request from iOS, Linux and Windows as well as from the JVM.

### Compared with the official client

Dependency stacks verified against `io.qdrant:client:1.18.3`.

| | Kdrant (`kdrant-transport-rest`) | Official `io.qdrant:client` |
| --- | --- | --- |
| Wire protocol | REST/HTTP over Ktor CIO | gRPC (HTTP/2) |
| Heavy dependencies | none, pure Kotlin (Ktor + kotlinx) | `grpc-netty-shaded` (bundled Netty), `grpc-protobuf`/`grpc-stub`, `protobuf-java`, Guava, slf4j |
| Approximate added footprint | 3 to 5 MB | 15 to 20 MB of transitive jars, shaded Netty about 9 MB alone |
| API style | `suspend` functions and `Flow`, type-safe DSL | `ListenableFuture<T>`, protobuf builders |
| Models | `kotlinx-serialization` data classes | generated protobuf messages |
| GraalVM native image | **37 ms** from process start to first search, in a 42 MB static binary | needs gRPC, Netty and protobuf native configuration you write and maintain |

That last row is a CI job rather than an adjective: [`native-image`](.github/workflows/ci.yml) compiles
[`example-native-image`](example-native-image/) with `--no-fallback` and makes it search a real Qdrant
on every change, so the day a dependency starts reflecting, the build fails instead of the sentence
quietly becoming false. Nothing is required of you: `kdrant-transport-rest` ships the one reflection
registration kotlinx-serialization needs, generated from its own classes rather than written by hand.

For raw throughput and long-lived streaming, gRPC still wins, and that case has an answer inside Kdrant:
`kdrant-transport-grpc` is the same `QdrantClient` behind the same API. For typical RAG and
embedding-search workloads, REST trades the wire for a fraction of the footprint.

## Installation

Requires JDK 17+. Artifacts are on Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kdrant-transport-rest:2.2.0")
}
```

`kdrant-transport-rest` brings `kdrant-core` with it; it is the only dependency you add. You also need
a running Qdrant:

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

6333 is the REST port and 6334 the gRPC one; map both and either engine connects.

### The modules

Everything below is optional and additive.

| Artifact | What you get |
| --- | --- |
| `kdrant-transport-rest` | **The one to start with.** The REST engine and the `Kdrant(...)` factory. Multiplatform. Brings `kdrant-core` with it. |
| `kdrant-transport-grpc` | The opt-in gRPC engine and the `KdrantGrpc(...)` factory, for when throughput is the bottleneck. JVM only, because it is grpc-java. |
| `kdrant-core` | The public API, models, DSLs and the `QdrantTransport` seam. Multiplatform. You rarely depend on it directly. |
| `kdrant-spring-boot-starter` | Spring Boot auto-configuration: `kdrant.*` properties and a ready `QdrantClient` bean. |
| `kdrant-spring-ai` | A Spring AI `VectorStore` backed by Kdrant, metadata filters included. |
| `kdrant-langchain4j` | A LangChain4j `EmbeddingStore` backed by Kdrant, metadata filters included. |
| `kdrant-koog` | A [Koog](https://github.com/JetBrains/koog) document storage where Qdrant runs the search instead of the agent scoring in memory. |
| `kdrant-micrometer` | A timer per operation, over either engine, tagged by the operation rather than by a URL. |
| `kdrant-otel` | One OpenTelemetry client span per operation, over either engine, carrying no payload or vector data. |
| `kdrant-migrate` | Re-embed a collection into a new one, resume after an interruption, verify, and move the alias only once the verification passed. |
| `kdrant-cli` | A single static binary for the operations that are not requests. Not on Maven Central; the binaries are attached to each [release](https://github.com/NaCode-Studios/Kdrant/releases). |
| `kdrant-bom` | A platform that keeps the versions above aligned. Import it and drop the versions. |

```kotlin
dependencies {
    implementation(platform("io.github.nacode-studios:kdrant-bom:2.2.0"))
    implementation("io.github.nacode-studios:kdrant-transport-rest")
    implementation("io.github.nacode-studios:kdrant-spring-ai")
}
```

### Choosing an engine

REST is the default and the right answer for most applications: a smaller dependency set, no native
configuration under GraalVM, and every operation available.

```kotlin
val qdrant: QdrantClient =
    if (useGrpc) KdrantGrpc(host = "localhost")   // gRPC, port 6334
    else Kdrant(host = "localhost")               // REST, port 6333
```

Every example below reads the same either way. Two differences matter before you switch. The port is
6334 rather than 6333, and nothing rewrites it for you. And Qdrant serves fourteen operations over HTTP
only: telemetry, Prometheus metrics, the two issues calls, snapshot recovery, the snapshot and
storage-snapshot transfers, and the six shard-scope snapshot operations. The gRPC engine refuses each of
them by name rather than degrading quietly.

### Platforms

`kdrant-core`, `kdrant-transport-rest` and `kdrant-migrate` publish one artifact per target: the JVM,
`iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `macosX64`, `linuxArm64`, `linuxX64` and
`mingwX64`. An iOS or Linux application depends on the same coordinate a JVM one does, and a Gradle
build resolves the right variant with no change. A Maven build names the artifact directly and wants
the `-jvm` one: `kdrant-core-jvm`, `kdrant-transport-rest-jvm`.

Two consequences belong here rather than in a stack trace. On Apple platforms the engine is
NSURLSession, so App Transport Security applies and a plaintext `http://` Qdrant is refused by the
platform before Kdrant sees the request. On Linux the engine is Curl, which links against the system
libcurl, present on every mainstream distribution and worth checking in a slim container image.

There is no Kotlin/JS target, and that is a decision rather than a gap. A browser cannot reach a Qdrant
without CORS on the server, a Qdrant reachable from a browser is reachable from anyone who opens the
developer tools, and an API key shipped to a browser is a published key. The answer changes if Qdrant
grows a browser-facing authorisation model that keeps the credential off the page, or if someone wants
Kdrant on Node.

### Qdrant versions

The contract both engines are held to runs against the four most recent Qdrant minors, and the result
is published whichever way it goes.

<!-- qdrant-matrix:start -->
| Qdrant | The shared client contract |
| --- | --- |
| `v1.19.0` | **39/39 pass** |
| `v1.18.3` | **39/39 pass** |
| `v1.17.1` | **39/39 pass** |
| `v1.16.3` | **39/39 pass** |

Written by `QdrantVersionMatrixIntegrationTest` from the run on the `2.2.0` merge commit. Regenerate it
with `KDRANT_UPDATE_COMPAT=1 ./gradlew :kdrant-transport-rest:jvmTest --tests '*QdrantVersionMatrix*'`.
<!-- qdrant-matrix:end -->

## Usage

### Connecting

```kotlin
val qdrant: QdrantClient = Kdrant(host = "localhost", port = 6333) {
    apiKey = "..."          // sent as the api-key header; omit for a local, unauthenticated node
    useTls = true           // required when sending a credential over a network
    requestTimeout = 10.seconds
}
```

`QdrantClient` is `AutoCloseable`; use it with `use { }` or close it explicitly.

`useTls` decides whether to speak HTTPS; `trustAnchors` decides which certificate to accept. The
default is the platform's own store, which is right for Qdrant Cloud. A private CA, a self-signed
staging node, or certificate pinning are named instead:

```kotlin
val qdrant = Kdrant(host = "qdrant.internal", port = 6333) {
    useTls = true
    trustAnchors = TrustAnchors.Pem(File("company-ca.pem").readText())
}
```

Every target honours `TrustAnchors.System`. The JVM honours all three; Linux honours a PEM bundle;
on iOS, macOS and Windows the trust store belongs to the platform, so a private CA goes into the
keychain or the machine store and Kdrant refuses the configuration rather than falling back to system
trust and looking like it complied. `TrustAnchors` names the store each engine reads.

### Collections

```kotlin
qdrant.createCollection("articles") {
    vector { size = 1_536; distance = Distance.COSINE }
    onDiskPayload = true
}

qdrant.createCollection("multimodal") {
    namedVector("text") { size = 768; distance = Distance.COSINE }
    namedVector("image") { size = 512; distance = Distance.DOT }
}
```

Create-if-absent is race-tolerant, and there is a shorthand for the common case and a non-throwing read:

```kotlin
qdrant.createCollectionIfNotExists("articles") { vector { size = 1_536; distance = Distance.COSINE } }
qdrant.createCollection("quickstart", size = 1_536)      // single vector, COSINE
val info = qdrant.getCollectionOrNull("articles")        // null instead of throwing if absent
```

### Payload indexes

Filtering on an unindexed payload field is a full scan. An index is one call, and the parameters that
index type accepts are part of it:

```kotlin
qdrant.createPayloadIndex("articles", "lang") { keyword { isTenant = true; onDisk = true } }
qdrant.createPayloadIndex("articles", "body") { text { tokenizer = Tokenizer.WORD; phraseMatching = true } }
```

Two of those decide whether a query works rather than how fast it is. A `matchPhrase` filter matches
nothing unless the text index was built with `phraseMatching = true`, and a multi-tenant collection is
only laid out per tenant if its tenant field says `isTenant = true`. `onDisk` is the difference between
an index that has to fit in RAM and one that does not.

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

Large batches are split automatically to stay under Qdrant's request-size limit.

### Ingesting more than fits in memory

`upsert` sends what it is given. `ingest` owns the parts you would otherwise write yourself: batching
by count and by size, a bound on requests in flight, retry of a batch rather than of the stream, and a
token that says where to carry on from.

```kotlin
var token = loadCheckpoint()                       // null on a first run

val report = qdrant.ingest("articles", pointsFromDisk, concurrency = 4, resumeFrom = token) {
    saveCheckpoint(it)                             // called as the acknowledged prefix grows
}
```

The token counts only an unbroken prefix, and it is a lower bound on what the collection holds rather
than an equality: a batch cancelled in flight may already have been applied. Resuming therefore
re-sends a few points that are already there, which upsert makes free, and can never skip a point the
server did not write.

### Server-side inference

Where the deployment has an inference provider configured, a point can carry the text and the name of a
model instead of a vector, and a query can do the same. The embedding happens in Qdrant.

```kotlin
qdrant.upsert("articles", wait = true) {
    point(1) { document("the text to embed", model = "jinaai/jina-embeddings-v2-base-en") }
}

val hits = qdrant.search("articles") {
    queryDocument("what to look for", model = "jinaai/jina-embeddings-v2-base-en")
    limit = 5
}
```

A plain Qdrant container has no provider and refuses these requests.

### Filters

The filter DSL mirrors Qdrant's filtering model and powers both `search` and delete-by-filter:

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

Supported conditions include exact, any, except and full-text match, numeric and datetime ranges,
`values_count`, geo bounding-box, radius and polygon, `is_empty` and `is_null`, `has_id`, `has_vector`,
per-element `nested` filters, and recursive `filter { }` sub-groups.

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

Decode each hit's payload straight into your own type with `searchAs`, or `payloadAs` on a single hit:

```kotlin
@Serializable data class Article(val title: String, val lang: String)

val articles: List<Hit<Article>> = qdrant.searchAs<Article>("articles") {
    query(queryVector); limit = 5
}
```

Hybrid search fuses several `prefetch` sources with Reciprocal Rank Fusion or DBSF:

```kotlin
val hits = qdrant.search("articles") {
    prefetch { query(denseVector); using = "dense"; limit = 50 }
    prefetch { querySparse(indices, values); using = "keywords"; limit = 50 }
    rrf()            // Reciprocal Rank Fusion; or dbsf()
    limit = 10
}
```

Declare the two with `namedVector(...)` and `sparseVector("keywords") { modifier = Modifier.IDF }` and
that is dense plus keyword hybrid search. You can also query by a stored point's vector
(`query(PointId.num(1))`), `orderBy("field")`, `sample()`, or a multi-vector (`queryMulti(...)`).

### Scrolling, deleting, counting

```kotlin
qdrant.scroll("articles", pageSize = 256) {
    filter { must { "lang" eq "en" } }
}.collect { record -> /* ... */ }                       // a cold Flow, paged transparently

qdrant.delete("articles", ids = listOf(PointId.num(1)))
qdrant.delete("articles") { must { "lang" eq "en" } }   // by filter

val total = qdrant.count("articles")
val points = qdrant.retrieve("articles", ids = listOf(PointId.num(1), PointId.num(2)))
```

### Scoped access

`apiKey` is the cluster's master key: full read and write over every collection. A Qdrant JWT is the
narrower credential: read-only, one collection, or a payload filter deciding which points the caller
may see at all, which is how one tenant's search is kept from reading another tenant's points.

```kotlin
val readOnly = Kdrant(host = "qdrant.internal") {
    bearerToken = tokenFromYourTokenService()
    useTls = true
}
```

The two are mutually exclusive, both engines send whichever is set, and a credential over plaintext
HTTP is refused unless the host is a loopback address. Minting and rotating tokens is Qdrant's job; see
its [security guide](https://qdrant.tech/documentation/guides/security/).

### Error handling

```kotlin
try {
    qdrant.upsert("articles") { /* ... */ }
} catch (e: KdrantException.CollectionNotFound) {
    // the collection does not exist
} catch (e: KdrantException.Forbidden) {
    // the credential is valid and this operation is not in its scope
} catch (e: KdrantException.Unauthorized) {
    // missing or wrong credential. Forbidden is a subclass, so order these two this way round
}
```

Every `KdrantException` says whether the same request could succeed later, so a caller does not have to
keep its own list of which failures are worth waiting out:

```kotlin
catching { qdrant.search("articles") { query(queryVector) } }
    .onFailure { if (it is KdrantException && it.retryable) scheduleRetry() else alert(it) }
```

`retryable` is a statement about the server, not about your data: retrying a read is always safe, and
retrying a write is safe where the write is idempotent, which an upsert keyed by point id is.
`catching { }` is a coroutine-safe `runCatching` that always re-throws `CancellationException`.

### Observability

```kotlin
val qdrant = Kdrant(
    host = "localhost",
    decorateTransport = kdrantTracing(openTelemetry, serverAddress = "localhost", serverPort = 6333),
)
```

One client span per operation with OpenTelemetry's database attributes, or one timer per operation with
`kdrantMetrics(registry)` from `kdrant-micrometer`. Both sit on the transport seam, so the same call
over `KdrantGrpc` produces the same span and the same timer. No payload value, no vector and no filter
ever reaches an attribute or a tag: those are exported to a backend many people can read, and the whole
point of a filter is often that it names a tenant.

`kdrant-otel` depends on the OpenTelemetry API rather than the SDK, so the exporter stays yours.

### Migrating a collection to a new embedding

A collection's vector size is fixed at creation, so changing embedding model means a second collection
and an alias swap. `kdrant-migrate` is that procedure:

```kotlin
val report = qdrant.migrateCollection(
    from = "docs-768",
    to = "docs-1536",
    alias = "docs",
    createTarget = { vector { size = 1536; distance = Distance.COSINE } },
    checkpoints = FileMigrationCheckpointStore(Path("/var/lib/kdrant")),
) { record ->
    PointStruct(record.id, VectorData.Dense(embed(record.text())), record.payload)
}
```

Readers go through the alias throughout and see no gap. The copy resumes from its cursor after an
interruption, and the alias moves only after the counts match and a sample of queries returns the same
neighbours from both collections above a stated recall. If the check fails,
`MigrationVerificationFailed` is thrown with the numbers in it and the alias stays where it was.

### The command line

Moving a collection between clusters, taking a backup, or looking at a node that is misbehaving are
things done with a terminal open, and writing a Kotlin program to do them is the wrong shape. `kdrant`
is one static binary per platform, attached to each
[release](https://github.com/NaCode-Studios/Kdrant/releases). No JVM, no classpath, no install step:

```bash
curl -fsSL -o kdrant https://github.com/NaCode-Studios/Kdrant/releases/latest/download/kdrant-linux-x64
chmod +x kdrant

export QDRANT_API_KEY=...          # a key on a command line is a key in the shell history
./kdrant migrate articles articles-v2 --alias articles --checkpoint /var/tmp/articles.checkpoint
```

That is the whole command to move a collection. It creates the target from the source's own vectors,
copies in id order, remembers where it got to, checks the counts and the recall, and moves the alias
only once the check passes. `--shards` and `--replicas` override the source's layout, which is what
makes it a re-shard. It cannot embed, so it moves what does not need new vectors: a re-shard, a config
change, a copy between clusters.

`kdrant collections`, `kdrant scroll` and `kdrant snapshot create|list|download|restore|delete` are the
rest of it; `kdrant --help` prints the flags. It is not a query tool, because Qdrant's own dashboard is
better at that and is already running next to the server.

## Architecture

The wire lives behind one interface, `QdrantTransport`, and everything above it is protocol-neutral:

```
kdrant-core          QdrantClient, models, DSLs, KdrantException, QdrantTransport
   |                 no wire-protocol knowledge · JVM + 8 Kotlin/Native targets
   +-- kdrant-transport-rest    Ktor         Kdrant(host)        JVM + 8 native targets
   +-- kdrant-transport-grpc    grpc-kotlin  KdrantGrpc(host)    JVM
   +-- kdrant-otel              decorator    kdrantTracing(...)  either engine
   +-- kdrant-micrometer        decorator    kdrantMetrics(...)  either engine
```

Adding the gRPC engine changed no line of `kdrant-core`, and the same behavioural suite runs against
both engines against the same Qdrant, so choosing between them is a footprint and throughput decision
rather than a feature one. The seam is also where anything above the wire goes: `decorateTransport` on
either factory is the hook a decorator of your own uses.

## Roadmap

The plan is on the [Kdrant board](https://github.com/orgs/NaCode-Studios/projects/4), one item per
milestone, each with a testable exit criterion, including the ideas that were declined and the argument
that settled each one. Every tier is a [milestone](https://github.com/NaCode-Studios/Kdrant/milestones)
in this repository.

What has shipped is in the [CHANGELOG](CHANGELOG.md), version by version. What a version may change is
in [STABILITY.md](STABILITY.md).

## Building and testing

```bash
./gradlew build         # compile, run unit tests, lint (ktlint + detekt), verify public API
./gradlew apiCheck      # check the tracked public API in *.api and *.klib.api
./gradlew apiDump       # regenerate them after an intentional public-API change, on macOS
./gradlew ktlintFormat  # auto-fix formatting before committing
```

Unit tests need no external services. Integration tests spin up a real Qdrant with
[Testcontainers](https://testcontainers.com) and are skipped when Docker is unavailable.

## Contributing

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Please run `./gradlew build` before
opening a pull request, and if you change the public API, run `./gradlew apiDump` on macOS and commit
the updated dumps.

## License

Licensed under the [Apache License 2.0](LICENSE). Brand assets (wordmark, symbol, and the colour and
type tokens) are in [`docs/brand`](docs/brand).

## Sponsor

If Kdrant is useful to you, consider [sponsoring NaCode Studios](https://github.com/sponsors/NaCode-Studios).
