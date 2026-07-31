# Migrating from `io.qdrant:client`

This guide maps the official Java client onto Kdrant, operation by operation. It assumes you have a
working application on `io.qdrant:client` and want to know exactly what changes.

Three things change and nothing else does. The port moves from gRPC's `6334` to REST's `6333`. Every
`ListenableFuture<T>` becomes a `suspend` function, and every stream becomes a `Flow`. Protobuf
builders and the static factory imports (`id`, `value`, `vectors`, `range`) become a typed Kotlin DSL.
Your collections, points, payloads and filters are untouched: both clients talk to the same server, so
you can run them side by side against the same Qdrant and move one call site at a time.

If you want to keep gRPC, only the second and third of those changes apply. Depend on
`kdrant-transport-grpc` and build the client with `KdrantGrpc(host)` instead of `Kdrant(host)`; the port
stays `6334` and everything else in this guide reads the same, because the API above the wire is the
same API. What that engine cannot do is listed under [What this is not](#what-this-is-not).

## What this is not

The gRPC engine is not the whole of Qdrant. Eleven operations are served over HTTP only — telemetry,
Prometheus metrics, the issues endpoint, snapshot recovery, snapshot download and upload, and the
shard-scope snapshots — and on the gRPC engine each throws rather than pretending. If you need any of
them, use the REST engine for the whole client or for that one call. The footprint trade-off between
the two is set out in the [README](../README.md#footprint-vs-the-official-client).

Cluster support covers a collection's shard distribution: reading it, moving and replicating shards,
and creating or dropping custom sharding keys. The node-level calls that administer the raft cluster
itself, adding and removing peers, have no Kdrant equivalent and are not planned; that is a job for
Qdrant's own tooling rather than for a client library.

## Installation

Replace the dependency. Kdrant needs JDK 17, where the official client needs 8.

```kotlin
dependencies {
    // implementation("io.qdrant:client:1.18.3")
    implementation("io.github.nacode-studios:kdrant-transport-rest:1.2.0")
}
```

## Creating a client

```java
// io.qdrant:client — gRPC, port 6334
QdrantClient client =
    new QdrantClient(QdrantGrpcClient.newBuilder("localhost").withApiKey("<apikey>").build());
```

```kotlin
// Kdrant — REST, port 6333
val qdrant = Kdrant(host = "localhost", port = 6333) {
    apiKey = "<apikey>"
    useTls = true
}
```

Both implement `AutoCloseable`, and a client is meant to be created once and shared. Kdrant refuses to
send an API key over plaintext HTTP: setting `apiKey` without `useTls` fails at construction rather
than leaking the key on the first request.

Connection tuning splits across two places. Timeouts and retries are connection settings and live in
the config block (`requestTimeout`, `connectTimeout`, `socketTimeout`, `maxRetries`, `retryBaseDelay`).
Pool sizing belongs to the engine and is a parameter of the factory (`maxConnectionsPerRoute`,
`keepAliveTime`), so that `KdrantConfig` stays meaningful for an engine with no pool.

## Creating a collection

```java
client.createCollectionAsync("docs",
    VectorParams.newBuilder().setDistance(Distance.Cosine).setSize(768).build())
  .get();
```

```kotlin
qdrant.createCollection("docs") { vector { size = 768; distance = Distance.COSINE } }
```

A rerunnable bootstrap script wants `ensureCollection` instead: it creates the collection when it is
missing, and when it is already there it checks that the vectors match what you asked for and fails if
they do not.

```kotlin
qdrant.ensureCollection("docs") { vector { size = 768; distance = Distance.COSINE } }
```

## Upserting points

```java
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

List<PointStruct> points = List.of(
    PointStruct.newBuilder()
        .setId(id(1))
        .setVectors(vectors(0.32f, 0.52f, 0.21f, 0.52f))
        .putAllPayload(Map.of("color", value("red"), "rand_number", value(32)))
        .build());

client.upsertAsync("docs", points).get();
```

```kotlin
qdrant.upsert("docs", wait = true) {
    point(1) {
        vector(0.32f, 0.52f, 0.21f, 0.52f)
        payload("color" to "red", "rand_number" to 32)
    }
}
```

The static `id` / `value` / `vectors` factories have no counterpart: the DSL infers the point id type
from what you pass (`Long` or `ULong` for numeric ids, `String` for UUIDs) and payload values from the
pair. The `vector(vararg Float)` overload keeps the floats in a `FloatArray` and never boxes them.

For a batch that does not fit in memory, hand `upsert` a `Flow<PointStruct>` or a `Sequence` and the
engine chunks it under Qdrant's payload cap. That chunking is not atomic across chunks; upsert is
idempotent per id, so retrying the whole call is safe.

## Searching

```java
List<ScoredPoint> points = client.searchAsync(
    SearchPoints.newBuilder()
        .setCollectionName("docs")
        .addAllVector(List.of(0.6235f, 0.123f, 0.532f, 0.123f))
        .setLimit(5)
        .build())
  .get();
```

```kotlin
val points = qdrant.search("docs") {
    query(0.6235f, 0.123f, 0.532f, 0.123f)
    limit = 5
}
```

Kdrant sends every search through Qdrant's `/points/query` endpoint, the one that also carries hybrid
fusion, recommend, discover and context queries, so the same `search { }` covers all of them rather
than a method per shape.

## Filtering

```java
import static io.qdrant.client.ConditionFactory.range;

Filter.newBuilder()
    .addMust(range("rand_number", Range.newBuilder().setGte(3).build()))
    .build();
```

```kotlin
filter { must { "rand_number" gte 3 } }
```

The four clauses keep Qdrant's names (`must`, `should`, `mustNot`, `minShould`) and every condition
type is available, including `nested`, `hasId`, `hasVector`, the geo conditions and recursive
sub-filters. An empty clause is rejected rather than serialized, because an empty filter matches every
point and delete-by-filter would then empty the collection.

## Reading many points

```java
client.scrollAsync(ScrollPoints.newBuilder().setCollectionName("docs").setLimit(64).build()).get();
// then re-issue with the returned offset, page by page
```

```kotlin
qdrant.scroll("docs", pageSize = 64).collect { record -> /* ... */ }
```

`scroll` returns a cold `Flow` and follows the cursor itself, so there is no pagination loop to write.
For a job that has to resume where it stopped, order the scroll by a payload key: pagination then
follows the order value, and `startFrom` picks it back up.

```kotlin
qdrant.scroll("docs", pageSize = 256) { orderBy("ts", startFrom = lastSeenTimestamp) }
```

## Errors

The official client reports failures as an `ExecutionException` wrapping a gRPC `StatusRuntimeException`,
which you unwrap and inspect by status code. Kdrant throws a sealed `KdrantException`, so the failure
modes can be handled exhaustively and a missing collection is distinguishable from a bad request
without string matching:

```kotlin
try {
    qdrant.search("docs") { query(vector) }
} catch (e: KdrantException.CollectionNotFound) {
    // ...
} catch (e: KdrantException.Timeout) {
    // ...
}
```

`catching { }` wraps a call into a `Result` when that reads better at the call site. Retries for
transient failures (HTTP 429/502/503/504 and I/O errors) happen inside the client with exponential
backoff, honouring `Retry-After`; what surfaces is the failure that survived them.

## Method reference

| `io.qdrant:client` | Kdrant |
| --- | --- |
| `createCollectionAsync(name, params)` | `createCollection(name) { vector { … } }` |
| — | `ensureCollection(name) { vector { … } }` |
| `deleteCollectionAsync(name)` | `deleteCollection(name)` |
| `collectionExistsAsync(name)` | `collectionExists(name)` |
| `getCollectionInfoAsync(name)` | `getCollection(name)` |
| `listCollectionsAsync()` | `listCollections()` |
| `upsertAsync(name, points)` | `upsert(name) { point(…) { … } }` |
| `searchAsync` / `queryAsync` / `recommendAsync` / `discoverAsync` | `search(name) { query(…) }` / `{ recommend { … } }` / `{ discover { … } }` |
| `searchBatchAsync` / `queryBatchAsync` | `searchBatch(name) { search { … } }` |
| `searchGroupsAsync` / `queryGroupsAsync` | `searchGroups(name, groupBy = …) { … }` |
| `scrollAsync(ScrollPoints)` | `scroll(name, pageSize) { … }` (a `Flow`) |
| `retrieveAsync(name, ids, …)` | `retrieve(name, ids, …)` |
| `countAsync(name, filter, exact)` | `count(name, exact) { … }` |
| `deleteAsync(name, ids)` | `delete(name, ids)` |
| `setPayloadAsync` / `overwritePayloadAsync` / `deletePayloadAsync` / `clearPayloadAsync` | same names, taking a `DeleteSelector` |
| `updateVectorsAsync` / `deleteVectorsAsync` | same names |
| `batchUpdateAsync(…)` | `batchUpdate(name) { … }` |
| `createPayloadIndexAsync(…)` | `createPayloadIndex(name, field, schema)` |
| `updateAliasesAsync(…)` | `updateAliases { createAlias(…); deleteAlias(…) }` |
| `createSnapshotAsync(name)` | `createSnapshot(name)` |
| `facetAsync(…)` | `facet(name, key)` |
| `close()` | `close()`, or `use { }` |

## Framework integrations

If you reached Qdrant through Spring AI or LangChain4j rather than directly, swap the store rather
than the client: `KdrantVectorStore` implements Spring AI's `VectorStore` and `KdrantEmbeddingStore`
implements LangChain4j's `EmbeddingStore<TextSegment>`, both including metadata-filter translation.
`KdrantDocumentStorage` does the same for a Koog agent's document storage. The Spring Boot starter
configures the client from `application.yml`.
