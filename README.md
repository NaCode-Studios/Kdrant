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
classpath whether you needed the throughput or not. Kdrant is the client you'd actually want to write
Kotlin against: `suspend` functions, a type-safe DSL, `kotlinx-serialization` models, and a wire you
pick. The default engine is pure-Kotlin REST and pulls in no gRPC, no protobuf and no Netty; the gRPC
engine is one dependency away when throughput is the bottleneck. Both are the same `QdrantClient` and
are held to the same behavioural test suite.

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

Kdrant stores and searches vectors. `embedding` above is a `List<Float>` from your own embedding
model, because **Kdrant computes no embeddings**: it bundles no model and depends on no inference
library. That is a statement about this client, not about what you can ask for. Where your Qdrant has
an inference provider configured, Kdrant can send the text and the model name and let the server
produce the vector, which is [server-side inference](#server-side-inference) below.

> **See it end to end.** [`example-rag`](example-rag/) is a small runnable Retrieval-Augmented-Generation
> service (ingest, embed, store, retrieve) built on Kdrant, with a `docker-compose` for Qdrant.

> **Status — `2.2`, stable.** Both engines are feature-complete: collections, `upsert`, the modern
> `/points/query` search (nearest, hybrid fusion, recommend/discover/context, batch, groups), sparse
> and multi-vectors, `scroll`, payload and vector management with the index parameters Qdrant takes,
> aliases, snapshots, cluster and sharding, service/analytics endpoints, server-side inference,
> resilient retries, a resumable ingest, and the full filter DSL, plus Spring Boot, Spring AI,
> LangChain4j, Koog, Micrometer, OpenTelemetry, migration and CLI modules. `kdrant-core`,
> `kdrant-transport-rest` and `kdrant-migrate` run on the JVM and eight Kotlin/Native targets, and
> their public API is tracked per target. The public API is stable under SemVer; see
> [STABILITY.md](STABILITY.md).

## Why Kdrant

- Every operation is a `suspend` function. Cancellation and timeouts are cooperative, and
  `CancellationException` is always propagated.
- Collections, points, payloads and filters are built declaratively through scope-isolated builders
  (`@DslMarker`) rather than verbose request objects.
- The default engine is pure Kotlin REST on Ktor and kotlinx-serialization, so gRPC, Netty and
  protobuf reach your classpath only if you ask for the gRPC engine by name.
- Failures surface as a sealed `KdrantException` you can handle exhaustively, whichever engine raised
  them.
- The wire protocol sits behind a `QdrantTransport` seam. That is not a claim about layering: it is why
  a second engine could be added without changing one line of `kdrant-core`, why tracing is one
  decorator rather than one per engine, and why a request can be sent from iOS, macOS, Linux and
  Windows as well as from the JVM.

### Footprint vs the official client

Dependency stacks verified against `io.qdrant:client:1.18.3`:

| | Kdrant (`kdrant-transport-rest`) | Official `io.qdrant:client` |
| --- | --- | --- |
| Wire protocol | REST/HTTP over Ktor CIO | gRPC (HTTP/2) |
| Heavy dependencies | none, pure Kotlin (Ktor + kotlinx) | `grpc-netty-shaded` (bundled Netty), `grpc-protobuf`/`grpc-stub`, `protobuf-java`, Guava, slf4j |
| Approx. added footprint | ~3-5 MB (Ktor + kotlinx-serialization; coroutines/stdlib are usually already present) | ~15-20 MB of transitive jars (shaded Netty ≈ 9 MB alone) |
| API style | `suspend` functions + `Flow`, type-safe DSL | `ListenableFuture<T>` (Guava), protobuf builders |
| Models | `kotlinx-serialization` data classes | generated protobuf messages |
| GraalVM native / cold start | **37 ms** from process start to first search, in a 42 MB static binary, measured in CI on every change | needs gRPC/Netty/protobuf native config you write and maintain; heavier cold start |

The GraalVM row used to read "friendly", and nobody had ever built an image. It is now a CI job
([`native-image`](.github/workflows/ci.yml)) that compiles [`example-native-image`](example-native-image/)
with `--no-fallback` and runs it against a real Qdrant, so the day a dependency starts reflecting the
build fails instead of the sentence quietly becoming false.

The measured run: a 42 MB static binary opens a client, creates a collection, upserts three points and
answers its first search **37 ms** after the process starts. There is no JVM in it and nothing to warm up.

Building it found the one thing that does reflect. Ktor resolves a serializer from the response type at
run time and kotlinx-serialization answers by looking for the generated `$$serializer`, which a native
image cannot find unless the class is registered. `kdrant-transport-rest` therefore ships that
registration in its own jar, generated from the classes on the classpath rather than written by hand, so
it cannot go stale and a consumer building a native image writes nothing.

For raw throughput and streaming, gRPC/HTTP2 still wins. That case now has an answer inside Kdrant:
`kdrant-transport-grpc` is an opt-in engine behind the same `QdrantClient`, so the column above stays
true of the default and a build that does not ask for gRPC never pays for it. For typical RAG and
embedding-search workloads, REST trades the wire for a fraction of the footprint and idiomatic Kotlin.

## Installation

Requires JDK 17+. Artifacts are published to Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kdrant-transport-rest:2.2.0")
}
```

`kdrant-transport-rest` brings in `kdrant-core` transitively; it is the only dependency you add.

### The modules

Everything below is optional and additive. Take the engine you want and nothing else.

| Artifact | What you get |
| --- | --- |
| `kdrant-transport-rest` | **The one to start with.** The REST engine and the `Kdrant(...)` factory. Multiplatform: CIO on the JVM, Darwin on iOS and macOS, Curl on Linux, WinHttp on Windows. Brings `kdrant-core` with it. |
| `kdrant-transport-grpc` | The opt-in gRPC engine and the `KdrantGrpc(...)` factory. Reach for it when throughput or long-lived streaming is the bottleneck. JVM only, because it is grpc-java. |
| `kdrant-core` | The public API, models, DSLs and the `QdrantTransport` seam, with no wire-protocol knowledge. Multiplatform. You rarely depend on it directly. |
| `kdrant-spring-boot-starter` | Spring Boot auto-configuration: `kdrant.*` properties and a ready `QdrantClient` bean. |
| `kdrant-spring-ai` | A Spring AI `VectorStore` backed by Kdrant, metadata filters included. |
| `kdrant-langchain4j` | A LangChain4j `EmbeddingStore` backed by Kdrant, metadata filters included. |
| `kdrant-koog` | A [Koog](https://github.com/JetBrains/koog) document storage where Qdrant runs the search instead of the agent scoring in memory. |
| `kdrant-micrometer` | A timer per Qdrant operation, over either engine, tagged by the operation the caller asked for rather than by a URL. |
| `kdrant-otel` | One OpenTelemetry client span per operation, over either engine, carrying no payload or vector data. |
| `kdrant-migrate` | Re-embed a collection into a new one, resume the copy after an interruption, verify it, and move the alias only once the verification passed. |
| `kdrant-cli` | A single static binary for the operations that are not requests: migrate, snapshot, collections, scroll. Not on Maven Central; the binaries are attached to each [release](https://github.com/NaCode-Studios/Kdrant/releases). |
| `kdrant-bom` | A platform that keeps the versions above aligned. Import it and drop the versions. |

```kotlin
dependencies {
    implementation(platform("io.github.nacode-studios:kdrant-bom:2.2.0"))
    implementation("io.github.nacode-studios:kdrant-transport-rest")
    implementation("io.github.nacode-studios:kdrant-spring-ai")
}
```

### Choosing an engine

REST is the default and the right answer for most applications: it is a smaller dependency set, it
needs no native configuration under GraalVM, and it is the engine every operation is available on.

```kotlin
val qdrant: QdrantClient =
    if (useGrpc) KdrantGrpc(host = "localhost")   // gRPC, port 6334
    else Kdrant(host = "localhost")               // REST, port 6333
```

Every example below reads the same either way, because the API above the wire is the same API. Two
differences are worth knowing before you switch. The **port** is 6334, not 6333, and nothing rewrites
it for you. And Qdrant serves fourteen operations over HTTP only: telemetry, Prometheus metrics, the
two issues calls, snapshot recovery, the snapshot and storage-snapshot transfers, and the six
shard-scope snapshot operations. The gRPC engine refuses each of them by name rather than degrading
quietly.

### Platforms

`kdrant-core`, `kdrant-transport-rest` and `kdrant-migrate` are Kotlin Multiplatform and publish one
artifact per target: the JVM, `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `macosX64`,
`linuxArm64`, `linuxX64` and `mingwX64`. An iOS or Linux application depends on the same coordinate a
JVM one does:

```kotlin
commonMain.dependencies {
    implementation("io.github.nacode-studios:kdrant-transport-rest:2.2.0")
}
```

The engine is chosen per target and the code above it is the same everywhere. Two consequences belong
here rather than in a stack trace. On Apple platforms the engine is NSURLSession, so App Transport
Security applies and a plaintext `http://` Qdrant is refused by the platform before Kdrant sees the
request: use TLS, or declare the exception yourself. On Linux the engine is Curl, which links against
the system libcurl, which is present on every mainstream distribution and worth checking in a slim
container image.

**There is no Kotlin/JS target, and that is a decision rather than a gap.** A browser cannot reach a
Qdrant without CORS configured on the server, a Qdrant reachable from a browser is a Qdrant reachable
from anyone who opens the developer tools, and an API key shipped to a browser is a published API key.
Those are facts about the deployment, not about the effort: adding the target is one line, and the line
would invite an architecture nobody should ship. The answer changes if Qdrant grows a browser-facing
authorisation model that does not put a usable credential in the page, or if someone wants Kdrant on
Node, where the key stays on the server and CORS does not apply. Neither has been asked for.

A Gradle build resolves the right variant from the plain coordinate and needs no change. A Maven build
names the artifact directly and wants the `-jvm` one: `kdrant-core-jvm`, `kdrant-transport-rest-jvm`.
The gRPC engine and the framework adapters are JVM-only and are unaffected.

The same behavioural contract that holds the two engines to one behaviour runs from a `linuxX64` and a
`macosArm64` binary against a real Qdrant in CI, because a klib that links has not been shown to work.

### Qdrant versions

The contract every engine is held to runs against the four most recent Qdrant minors, and the result
is published whichever way it goes. A cell that fails on an older server is what a reader on that
server needs to know, and a range like "1.16 and later" cannot say which part is affected.

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

The table is written by that command rather than by hand, and the `Qdrant version matrix` job in CI
runs the same suite on every push. The blocking check is separate: the integration job holds both
engines to the pinned version and to `latest`, and that one is red when it should be.

You also need a running Qdrant. For local development:

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

6333 is the REST port and 6334 the gRPC one; map both and either engine connects.

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

**TLS trust.** `useTls` decides whether to speak HTTPS; `trustAnchors` decides which certificate to
accept. The default is the platform's own store, which is right for Qdrant Cloud and for anything with
a publicly issued certificate. A private CA, a staging node with a self-signed certificate, or
certificate pinning are named instead:

```kotlin
val qdrant = Kdrant(host = "qdrant.internal", port = 6333) {
    useTls = true
    trustAnchors = TrustAnchors.Pem(File("company-ca.pem").readText())
}
```

Trust is the one setting where the platforms differ, so the support is stated rather than assumed. Every
target honours `TrustAnchors.System`; the JVM honours all three; Linux honours a PEM bundle through
libcurl and cannot pin, because Ktor's Curl engine exposes no option for it. On iOS, macOS and Windows
the store belongs to the platform, so a private CA goes into the keychain or the machine store, and
Kdrant refuses the configuration rather than falling back to system trust and looking like it complied.
`TrustAnchors` names which store each engine reads.

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

### Payload indexes

Filtering on an unindexed payload field is a full scan. An index is one call, and the parameters that
index type accepts are part of it:

```kotlin
qdrant.createPayloadIndex("articles", "lang") { keyword { isTenant = true; onDisk = true } }
qdrant.createPayloadIndex("articles", "body") { text { tokenizer = Tokenizer.WORD; phraseMatching = true } }
```

Two of those decide whether a query works rather than how fast it is. A `matchPhrase` filter matches
nothing unless the text index was built with `phraseMatching = true`, and a multi-tenant collection is
only laid out per tenant if its tenant field says `isTenant = true`. The rest are cost, `onDisk` above
all: it is the difference between an index that has to fit in RAM and one that does not.

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

### Server-side inference

Kdrant computes no embeddings. Qdrant can, where the deployment has an inference provider configured,
and from `2.2.0` Kdrant can ask it to: a point carries the text and the name of a model instead of a
vector, and a query does the same.

```kotlin
qdrant.upsert("articles", wait = true) {
    point(1) { document("the text to embed", model = "jinaai/jina-embeddings-v2-base-en") }
}

val hits = qdrant.search("articles") {
    queryDocument("what to look for", model = "jinaai/jina-embeddings-v2-base-en")
    limit = 5
}
```

The models, the providers and the cost of running them are Qdrant's side of the line and stay there. A
plain Qdrant container has no provider and refuses these requests, which is why the round trip is
tested only where one exists, while the request shapes are validated against Qdrant's own schema on
every build.

### Ingesting more than fits in memory

`upsert` sends what it is given. `ingest` owns the parts a caller should not have to write again:
batching by count and by size, a bound on requests in flight, retry of a batch rather than of the
stream, and a token that says where to carry on from.

```kotlin
var token = loadCheckpoint()                       // null on a first run

val report = qdrant.ingest("articles", pointsFromDisk, concurrency = 4, resumeFrom = token) {
    saveCheckpoint(it)                             // called as the acknowledged prefix grows
}
```

The measurement that decides whether it worked is not throughput. It is what happens when the process
is killed at point four hundred thousand and started again, which without a token is start over. The
token counts only an unbroken prefix, so a batch acknowledged while an earlier one is still being
retried never moves it, and resuming cannot skip a point the server did not write. Storing the token
is the caller's business, because where it goes is a question about their deployment.

### Filters

The filter DSL mirrors Qdrant's filtering model (`must` / `should` / `mustNot` / `minShould`, every
condition type, recursive nesting) and powers both `search` and delete-by-filter:

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

Hybrid search fuses several `prefetch` sources with Reciprocal Rank Fusion or DBSF:

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
supported too. Combine a dense and a sparse prefetch under `rrf()` for real dense plus keyword hybrid
search, after declaring them with `namedVector(...)` and
`sparseVector("keywords") { modifier = Modifier.IDF }`.

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

The two are mutually exclusive, both engines send whichever one is set, and a credential over plaintext
HTTP is refused unless the host is a loopback address, where nothing leaves the machine. Minting and
rotating tokens is Qdrant's job; see its
[security guide](https://qdrant.tech/documentation/guides/security/).

A refusal comes back as something you can act on:

```kotlin
try {
    qdrant.upsert("articles") { /* ... */ }
} catch (e: KdrantException.Forbidden) {
    // the token is valid and does not reach this far: retrying will not help
}
```

### Tracing

```kotlin
val qdrant = Kdrant(
    host = "localhost",
    decorateTransport = kdrantTracing(openTelemetry, serverAddress = "localhost", serverPort = 6333),
)
```

One client span per operation, named `<operation> <collection>`, with OpenTelemetry's database
attributes. It sits on the transport seam, so the same call over `KdrantGrpc` produces the same span.
`kdrant-otel` depends on the OpenTelemetry API rather than the SDK, so the exporter stays yours.

No payload value, no vector and no filter ever reaches an attribute: a span is exported to a backend
many people can read, and the whole point of a filter is often that it names a tenant.

### Migrating a collection to a new embedding

A collection's vector size is fixed at creation, so changing embedding model means a second collection
and an alias swap. `kdrant-migrate` is that procedure, with the two parts that are easy to get wrong
already handled:

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
interruption rather than starting over, and the alias moves only after the counts match and a sample of
queries returns the same neighbours from both collections above a stated recall. If the check fails,
`MigrationVerificationFailed` is thrown with the numbers in it and the alias stays where it was.

### The command line

Some operations are not requests. Moving a collection between clusters, taking a backup, or looking at
what is on a node that is misbehaving are things somebody does with a terminal open, and writing a
Kotlin program with a dependency and a jar to do them is the wrong shape.

`kdrant` is one static binary per platform, attached to each
[release](https://github.com/NaCode-Studios/Kdrant/releases). No JVM, no classpath, no install step:

```bash
curl -fsSL -o kdrant https://github.com/NaCode-Studios/Kdrant/releases/latest/download/kdrant-linux-x64
chmod +x kdrant

export QDRANT_API_KEY=...          # a key on a command line is a key in the shell history
./kdrant migrate articles articles-v2 --alias articles --checkpoint /var/tmp/articles.checkpoint
```

That is the whole command to move a collection: it copies in id order, remembers where it got to, checks
the counts and the recall, and moves the alias only once the check passes. It also cannot embed, so it
moves what does not need new vectors: a re-shard, a config change, a copy between clusters. Re-embedding
stays in [`kdrant-migrate`](#migrating-a-collection-to-a-new-embedding), where a program can supply the
model.

`kdrant collections`, `kdrant scroll`, and `kdrant snapshot create|list|download|restore|delete` are the
rest of it. `kdrant --help` prints the flags. It is not a query tool: Qdrant's own dashboard is better at
that and is already running next to the server.

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

**Retryable or terminal.** Every `KdrantException` says which it is, so a caller does not have to keep
its own list of which failures are worth waiting out:

```kotlin
catching { qdrant.search("articles") { query(queryVector) } }
    .onFailure { if (it is KdrantException && it.retryable) scheduleRetry() else alert(it) }
```

`retryable` is a statement about the server, not about your data: retrying a read is always safe, and
retrying a write is safe where the write is idempotent, which an upsert keyed by point id is.

Three failures exist because a degraded cluster used to be indistinguishable from a broken request.
`ReadOnly` is a node refusing writes while still serving reads, which is not the same event as a
credential being refused and is not fixed the same way. `ShardUnavailable` is a shard with no live
replica, where the collection exists and part of it is unreachable. And `PartiallyApplied` reports how
many points a large upsert wrote before it failed, so the choice between re-sending everything and
losing the rest is made with a number rather than a guess.

Prefer a `Result`? `catching { }` is a coroutine-safe `runCatching`: it wraps the outcome but always
re-throws `CancellationException`:

```kotlin
val hits = catching { qdrant.search("articles") { query(queryVector) } }.getOrElse { emptyList() }
```

## Architecture

The wire lives behind one interface, `QdrantTransport`, and everything above it is protocol-neutral:

```
kdrant-core          QdrantClient, models, DSLs, KdrantException, QdrantTransport
   |                 no wire-protocol knowledge · JVM + 8 Kotlin/Native targets
   +-- kdrant-transport-rest    Ktor         Kdrant(host)        JVM + 8 native targets
   +-- kdrant-transport-grpc    grpc-kotlin  KdrantGrpc(host)    JVM
   +-- kdrant-otel              decorator    kdrantTracing(...)  either engine
```

That is the arrangement the second engine tested. Adding gRPC changed no line of `kdrant-core`, and the
same behavioural suite runs against both engines against the same Qdrant, so the choice between them is
a footprint and throughput decision rather than a feature one. The exception is the set of operations
Qdrant serves over HTTP only, which the gRPC engine names.

It is also why the core compiles for iOS, macOS, Linux and Windows: code that never knew there was a
wire has nothing platform-specific to port. For two releases that was where it stopped, and those
targets got the models and the query DSL with nothing to put them on the wire. The REST engine now
compiles for all of them, because Ktor's client is one API across engines and only the engine had to be
chosen per target. The gRPC engine stays on the JVM, because grpc-java is.

Kotlin/JS is left out for a different reason rather than an accident of effort: the target brings the
only npm dependency graph this repository has, and that is a decision of its own.

The seam is also where anything above the wire goes. `kdrant-otel` is a decorator on `QdrantTransport`,
so one implementation traces both engines and would trace a third; `decorateTransport` on either
factory is the same hook for a decorator of your own.

## Roadmap

**Shipped (`2.0.0`).** Tier 5 closed the two things the transport seam existed to make possible.
`kdrant-transport-grpc` is an opt-in gRPC engine behind the same `QdrantClient`, generated from Qdrant's
own `.proto` files rather than wrapping the official client, so a REST build still resolves no gRPC, no
protobuf and no Netty, checked on every build rather than asserted. And `kdrant-core` moved to Kotlin
Multiplatform: the JVM plus eight Kotlin/Native targets, a migration that changed no public API at
all. Both engines are now held to one shared behavioural suite against a real Qdrant. The major bump is
for the artifact layout, not the API: `kdrant-core`'s JVM classes moved to `kdrant-core-jvm`, which a
Gradle build does not notice and a Maven build does. See
[STABILITY.md](STABILITY.md#upgrading-from-1-x).

On top of the `1.x` line: metadata-filter translation in the Spring AI and LangChain4j adapters,
contract tests validating every request body against Qdrant's OpenAPI document, `ensureCollection`, an
ordered `scroll` that resumes, `batchUpdate`, the `kdrant-micrometer` module, `X-Request-Id`
correlation, cluster and sharding, collection aliases, snapshots with streaming backup and restore, the
service, health and analytics endpoints, a `FloatArray` no-boxing hot path, the modern `/points/query`
engine, and typed-payload DX. The [CHANGELOG](CHANGELOG.md) has the version-by-version detail, and the
[migration guide from `io.qdrant:client`](docs/migrating-from-qdrant-client.md) has
[measured latency](benchmarks/README.md#measured-latency) behind it.

**Shipped (`2.1.0`).** Tier 7 makes three claims true that were previously only compiled, only argued
or only asserted. The REST engine runs on every target `kdrant-core` does, and
the shared behavioural contract runs from a Linux and a macOS native binary against a real Qdrant.
`KdrantConfig` takes a scoped JWT beside the master key, and a refusal arrives as
`KdrantException.Forbidden` rather than as a generic failure. `kdrant-otel` traces the transport seam,
`kdrant-migrate` moves a collection to a new embedding without taking reads down, a GraalVM native image
is built and made to search in CI, and every published POM description now names the platforms that
module actually has, checked on every build.

**Shipped (`2.2.0`).** Tiers 8 and 9 close the gap between a request Kdrant can build and one that has
been shown to work, and put the client's behaviour on record where a cluster is not healthy.
`createPayloadIndex` takes the parameters Qdrant takes, so `matchPhrase` matches and a multi-tenant
collection can be laid out per tenant. Sparse, multi-vector and reciprocal-rank-fusion hybrid search
are in the shared contract against a real server rather than asserted as request bodies. Metrics moved
to the transport seam, so a client built with `KdrantGrpc` reports for the first time. The public API
is tracked per native target, not only for the JVM, and `STABILITY.md` states what a `2.x` promise
means for a klib. TLS trust is configurable from every target that can honour it, points and queries
can name a document for the server to embed, an `ingest` owns batching, concurrency and resume, and a
degraded cluster reports itself as `ReadOnly`, `ShardUnavailable` or `PartiallyApplied` instead of as a
generic failure. `kdrant-cli` is a single static binary per platform for the operations that are not
requests, and the Kotlin/JS exclusion is argued [above](#platforms) rather than in a build file.

**Next.** Nothing is claimed yet; the board is where it gets decided.

The plan lives on the [Kdrant board](https://github.com/orgs/NaCode-Studios/projects/4), one item per milestone, each with its
exit criterion. Every tier is a [milestone](https://github.com/NaCode-Studios/Kdrant/milestones) in this repository. See
[STABILITY.md](STABILITY.md) for the versioning and stability policy.

## Building and testing

```bash
./gradlew build         # compile, run unit tests, lint (ktlint + detekt), verify public API
./gradlew apiCheck      # check the tracked public API in *.api
./gradlew apiDump       # regenerate *.api after an intentional public-API change
./gradlew ktlintFormat  # auto-fix formatting before committing
```

Unit tests need no external services. Integration tests spin up a real Qdrant with
[Testcontainers](https://testcontainers.com) and are skipped automatically when Docker is
unavailable.

## Contributing

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Please run `./gradlew build`
before opening a pull request, and if you change the public API, run `./gradlew apiDump` and commit
the updated `*.api` files.

## License

Licensed under the [Apache License 2.0](LICENSE). Brand assets (wordmark, symbol, and the colour and
type tokens) are in [`docs/brand`](docs/brand).

## Sponsor

If Kdrant is useful to you, consider [sponsoring NaCode Studios](https://github.com/sponsors/NaCode-Studios).
