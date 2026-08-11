# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **An ingest whose source dies now hands out the checkpoint it earned.** The batches still in flight
  when the source threw were cancelled where they stood, so whether a run reported any checkpoint at
  all depended on which request happened to come back first, and a run killed early enough could report
  none — leaving nothing to resume from in exactly the case the token exists for. The source's failure
  is now held until those batches have drained and reported, then thrown, which is what a batch failure
  already did and for the same reason: a batch cancelled after the server accepted it is a point the
  collection holds and no token counts.

## [2.2.0] - 2026-08-06

Tiers 8 and 9, complete. The theme is the distance between a request this client can build and one that
has been shown to work, and the client's behaviour where a cluster is not healthy. `matchPhrase` now
matches something. Sparse and hybrid search meet a server. Metrics report from both engines. The public
API is tracked per native target rather than only for the JVM, and the compatibility promise says what
it means for a klib. A degraded cluster reports itself. And there is a binary.

### Added

- **The payload index parameters Qdrant takes** (M43). `createPayloadIndex(name, field, schema)` sent a
  field name and a type, which was the entire request the client could build. A builder overload sends
  the rest: a text index chooses its tokenizer, token lengths, lowercasing and phrase matching; an
  integer index chooses whether it answers lookups, ranges or both; keyword and uuid indexes take
  `isTenant`, integer, float and datetime take `isPrincipal`, and all of them take `onDisk`. The
  four-argument call is unchanged and still there.
  Two of those decide whether a query works rather than how fast it is. `matchPhrase` was in the filter
  DSL and deliberately absent from the contract, because Qdrant matches a phrase only against an index
  built with `phrase_matching: true` and nothing could ask for one, so the filter was accepted and
  matched nothing. The contract now asserts it, over both engines, and the comment explaining why it
  could not is deleted rather than reworded.
- **Sparse, multi-vector and hybrid search in the shared contract** (M44). `sparseVector`,
  `querySparse`, `queryMulti` and reciprocal-rank fusion were public API for four releases with nothing
  but body assertions behind them, which is the gap between a request that is well formed and one that
  works. The contract creates a collection carrying a dense and a sparse vector, upserts points with
  both, runs an RRF hybrid query and a DBSF one, exercises IDF scoring where the server rescales what
  was sent, stores and queries a multi-vector collection, and asserts that a query naming a vector the
  collection lacks is refused. All of it over both engines against a real Qdrant.
- **Server-side inference** (M48). Qdrant takes `Document`, `Image` and `InferenceObject` wherever it
  takes a vector, and Kdrant could not express the request. `VectorData.Inference` and
  `QueryInterface.Inference` carry one, with `document(...)` on the point builder and `queryDocument(...)`
  on the search builder for the common case. Kdrant still embeds nothing: it bundles no model and takes
  no dependency on an inference library, and this carries a request naming what to embed and with which
  model exactly as a filter carries what to match. The round trip needs a provider configured on the
  server, so it runs where one exists and is skipped where it is not, while both request shapes are
  validated against Qdrant's own OpenAPI document on every build.
- **An ingest that owns batching, concurrency and resume** (M55). `QdrantClient.ingest` takes a `Flow`
  and owns the parts that were the caller's: batching by count and by serialized size, a bound on
  requests in flight, retry of a batch rather than of the stream, and an `IngestCheckpoint` naming the
  points the server acknowledged. The count advances only over an unbroken prefix, so a batch
  acknowledged while an earlier one is still being retried never moves it and a resumed run cannot skip
  a point that was never written.
  The token is a lower bound rather than an equality, and the difference is the whole safety argument. A
  batch that was in flight when a run died may already have been applied, and an acknowledgement that
  never came back cannot move a checkpoint, so the collection can hold more than the token claims.
  Resuming therefore re-sends a few points that are already there, which upsert makes free; a token that
  could over-claim would skip points and lose them without a trace. `batchUpdate` and `upsert` are
  unchanged and still there.
- **TLS a native consumer can configure** (M47). `useTls` decided whether to speak HTTPS and nothing
  decided which certificate to accept, and the documented escape hatch, `configureClient`, hands back a
  star-projected Ktor config that cannot open an engine-specific block. `KdrantConfig.trustAnchors`
  takes `TrustAnchors.System`, a `Pem` bundle, or a set of pinned public keys. The JVM honours all
  three, Linux honours a PEM bundle through libcurl, and iOS, macOS and Windows honour the system store
  their platform owns. A configuration a target cannot honour is refused when the client is built,
  naming the platform and the store to put the certificate in, rather than falling back to system trust
  and looking like it complied. The KDoc names the store each engine reads.
- **`KdrantException.retryable`, and three failures a degraded cluster used to hide** (M52).
  `ReadOnly` is a node refusing writes while still serving reads, which is not the same event as a
  credential being refused; `ShardUnavailable` is a shard with no live replica, where the collection
  exists and part of it is unreachable; `PartiallyApplied` reports how many points a split upsert wrote
  before it failed, so the choice between re-sending everything and losing the rest is made with a
  number. Every exception in the hierarchy now says whether the same request could succeed later.
  `kdrant-testkit` grew `QdrantCluster`, a real two-node cluster in Docker, and
  `DegradedClusterContract`, which stops a node and lowers a strict-mode ceiling and asserts what the
  client does. Both engines run it.
- **`StrictModeConfig`** on `createCollection` and `updateCollection`: the per-collection limits Qdrant
  enforces on the requests it accepts, including the disk and memory ceilings past which a node refuses
  writes and keeps serving reads. Added as part of M52, because it is what makes that state reachable
  from a test rather than from an incident.
  Its KDoc names which server versions enforce which limit, which is not decoration. Qdrant 1.18 refuses
  writes over the disk ceiling and Qdrant 1.19 does not, having deprecated that family in favour of a
  global quota API. The degraded-cluster contract found that by passing against the pinned version and
  failing against `latest`, which is the version matrix earning its place on its first run.
- **`kdrant-cli`** (M49). One static binary per platform for the operations that are not requests:
  `migrate`, `snapshot create|list|download|restore|delete`, `collections` and `scroll`. No JVM, no
  classpath, no install step. It is not published to Maven Central; the binaries are built on two
  runners, run against a real Qdrant before they are allowed near a release, attested with SLSA
  provenance and attached to the GitHub Release with their checksums. It cannot embed, so it migrates
  what does not need re-embedding, and the help text says so.
  `kdrant migrate` creates the target from the source's own vectors rather than asking for a size and a
  distance the person at the terminal did not choose, and `--shards` and `--replicas` override, which is
  what makes `kdrant migrate a b --shards 4` a re-shard. The first release build found that it did not:
  the migration refused because the target did not exist, which is true of every collection nobody has
  made yet and therefore of the whole command.
- **The client contract against four Qdrant versions** (M50). `QdrantVersionMatrixIntegrationTest` runs
  the shared contract against the four most recent minors and writes the result into the README between
  generated markers. It does not fail the build: a cell that is red against an older server is the
  information the matrix exists to publish, and a matrix that fails a release for being honest is a
  matrix that gets deleted. The blocking check is unchanged, against the pinned version and `latest`.
- **A benchmark against `io.qdrant:client`** (M51). Every number this repository published compared
  Kdrant to Kdrant. `OfficialClientComparisonBenchmark` runs both clients against the same server, the
  same data and the same JVM over a single search, a batch search, an upsert of a large batch and a full
  scroll. The official client is a benchmark dependency and reaches no published POM.

### Changed

- **Metrics moved to the transport seam** (M45). `kdrant-micrometer` was a Ktor client plugin, so a
  client built with `KdrantGrpc` published no metrics at all and nothing said so. It is now a
  `QdrantTransport` decorator, the same seam `kdrant-otel` traces, installed the same way:
  `decorateTransport = kdrantMetrics(registry)`.
  The tags change with it, and the cost belongs here rather than in a release note nobody reads. The
  `operation` tag stops being a route template such as `/collections/{collection}/points/query` and
  becomes the operation, `query`, which is the vocabulary `db.operation.name` already uses on the span.
  `method` and `status` are gone, because neither exists on a gRPC call and a tag present on one engine
  is a tag no dashboard can rely on. `outcome` gains `CANCELLED` and reports the exception subclass
  instead of an HTTP class. Dashboards built on the old values need editing. Installing through
  `configureClient` still works and is deprecated, with the replacement in the message.
- **A TLS failure is a `KdrantException`.** A refused certificate is not an `IOException` on every
  platform, so `CertPathValidatorException` and `CertificateException` used to escape the transport seam
  and reach a caller who had been told every failure there is a `KdrantException`. They are wrapped in
  `KdrantException.Transport` now, with the cause kept.
- **`STABILITY.md` states the `2.x` promise per artifact type** (M53), including what a minor may and
  may not do to a klib, why narrowing a declaration to fewer targets is a removal, what a Kotlin
  compiler upgrade is allowed to do, and what happens when a Qdrant change forces a signature change.
- **The Kotlin/JS exclusion is argued in the README** (M54) rather than in a comment in a build file. A
  browser cannot reach a Qdrant without CORS on the server, a Qdrant reachable from a browser is
  reachable from anyone, and an API key shipped to a browser is a published key. The README names what
  would change the answer; the build-file comment is deleted rather than duplicated.

### Deprecated

- `KdrantMetrics`, the Ktor client plugin, and `HttpClientConfig<*>.kdrantMetrics(...)` that installs
  it. Both keep working for one minor. Use `decorateTransport = kdrantMetrics(registry)`, which covers
  both engines.

### Internal

- `STABILITY.md` gains an [Upgrading from `2.1`](STABILITY.md#upgrading-from-21) section naming the
  twelve lines this release removes from the API dumps, all of which are a defaulted parameter changing
  the signature Kotlin emits or a class becoming open, and none of which is a capability going away. The
  count came from `git diff v2.1.0 v2.2.0 -- '*/api/*.api'` rather than from an impression.
- **The public API is tracked per native target** (M46). `apiDump` writes a `.klib.api` beside the JVM
  dump for every multiplatform module, merged and target-annotated, so a declaration present on some
  targets and not others is a diff rather than silence. The dump can only be regenerated on a host that
  builds the Apple targets: `CONTRIBUTING.md` says so, and the macOS job in CI runs `apiCheck` for
  exactly that reason.
- `kdrant-micrometer` gained a Testcontainers integration test that runs the same operation over both
  engines and compares the meter ids, which is the claim the module now rests on.
- The release workflow builds the CLI binaries on two runners, proves each against a real Qdrant, and
  attaches them to the GitHub Release with SLSA provenance and checksums.
- The benchmarks workflow exposes Qdrant's gRPC port, because the official client speaks it.
- Dependency and action bumps that landed between the tier work and the tag: Ktor 3.5.1 to 3.5.2,
  langchain4j 1.18.0 to 1.18.1, Guava 33.5.0 to 33.6.0, and `io.qdrant:client` 1.15.0 to 1.18.3, which
  is a benchmark dependency and reaches no published POM. Two GitHub Actions moved by a major:
  `actions/attest-build-provenance` v3 to v4 and `actions/download-artifact` v7 to v8, both in
  `release.yml`. A grouped minor or patch bump with green CI needs no entry; a major does, and a
  workflow that ships no API still decides how this project releases.


## [2.1.0] - 2026-08-02

Tier 7, complete. Four claims that were previously compiled, argued or asserted are now things a build
proves: the REST engine runs on every target `kdrant-core` does and the shared contract runs from a
native binary, a scoped token is a credential the client knows about, a native image is built and made
to search, and every published POM names the platforms that module actually has.

### Added

- **`kdrant-transport-rest` is multiplatform** (M38). `kdrant-core` had compiled for eight
  Kotlin/Native targets since `2.0.0`, and not one of them could send a request: the engine lived in
  `src/main` and was Ktor CIO on the JVM, so an iOS build got the models, the query DSL and the filter
  builders with nothing to put them on the wire. `RestQdrantTransport` is in `commonMain` now and the
  engine is chosen per target — CIO on the JVM, unchanged; Darwin on iOS and macOS; Curl on Linux;
  WinHttp on Windows. An iOS or Linux consumer depends on the same `kdrant-transport-rest` coordinate a
  JVM one does.
  Two engine choices have consequences worth knowing before a stack trace tells you: Darwin is
  NSURLSession and inherits App Transport Security, so a plaintext `http://` Qdrant is refused by the
  platform before Kdrant sees the request, and Curl links against the system libcurl, which a slim
  container image may not have. Kotlin/JS stays out for the reason already written in `kdrant-core`.
- **`kdrant-testkit` is multiplatform**, which is what makes the above more than a compilation
  exercise. The behavioural contract both engines are held to moved to `commonMain` as
  `QdrantClientContractSuite`, which knows no test framework; the JUnit and Testcontainers wrapper
  stays on the JVM and still declares one test per behaviour. CI runs the same suite from a `linuxX64`
  and a `macosArm64` binary against a real Qdrant.
- **Scoped access** (M39). `KdrantConfig` takes a `bearerToken` beside `apiKey`, mutually exclusive
  with it: a Qdrant JWT narrowed to read-only, to named collections, or to a payload filter deciding
  which points a caller may see at all. Both engines send it — `Authorization: Bearer` over REST, the
  same header as gRPC metadata — because the credential belongs to the config rather than to the wire.
  `kdrant-testkit` signs one for tests through `QdrantJwt`; minting tokens for a running system stays
  Qdrant's job.
- **`KdrantException.Forbidden`**, a subclass of `Unauthorized`, for HTTP 403 and gRPC
  `PERMISSION_DENIED`. A read-only token refused on a write is a different fact from a missing key, and
  only one of them is worth retrying. Being a subclass keeps an existing
  `catch (e: KdrantException.Unauthorized)` catching it and keeps a `when` over the sealed hierarchy
  exhaustive.
- **`kdrant-otel`** (M41), a new module: one OpenTelemetry client span per operation, on the transport
  seam, so one implementation covers both engines and a third would inherit it. Attributes follow
  OpenTelemetry's database conventions rather than an invented vocabulary. No payload value, vector or
  filter reaches an attribute, and a failed span carries the exception type rather than the server's
  message, because Qdrant quotes the request back in its errors. It depends on the OpenTelemetry API,
  never the SDK, so the exporter stays the consumer's.
- **`kdrant-migrate`** (M42), a new module: `migrateCollection(from, to, alias)` copies a collection
  into one with a different vector size, verifies the result, and moves an alias so readers cross in one
  step. It resumes from a cursor after an interruption rather than starting over, and the alias moves
  only after the counts match and a sample of queries returns the same neighbours from both collections
  above a stated recall. A failed check throws `MigrationVerificationFailed` with the numbers in it and
  leaves the alias where it was: a tool that swaps because the copy finished without throwing is a tool
  that will one day point production at an empty collection.
- `decorateTransport` on `Kdrant(...)` and `KdrantGrpc(...)`, the hook `kdrant-otel` needs and the
  place a caching or rate-limiting decorator of your own goes.
- `ScrollBuilder.startAt`, the id cursor a resumable job over a collection needs. It came out of M42
  and is worth more than the migration.
- A GraalVM native image (M40). `example-native-image` is compiled with `--no-fallback` in CI and made
  to answer a real search against a real Qdrant, so the README's claim is a job that fails the day a
  dependency starts reflecting rather than a sentence in a table. Measured: **37 ms** from process start
  to first search, in a 42 MB static binary. The comparison table quotes that instead of the word
  friendly.
  Building it settled the claim in the second of the two ways it could go. One thing does reflect: Ktor
  resolves a serializer from the response type at run time, and kotlinx-serialization answers by looking
  for the compiler-generated `$$serializer`, which a native image cannot find unless the class is
  registered. `kdrant-transport-rest` now ships that registration in its own jar, generated from the
  classes on the classpath rather than written by hand, so a model added tomorrow is in the file the
  same day and a consumer building a native image writes nothing.

### Changed

- **Every published module's POM description now names the platforms that module actually has**, and
  `verifyPublishedDescription` fails the build when one stops being true. The description Maven Central
  serves for `kdrant-core:2.0.0` ends with "Core module for RAG and embedding search on the JVM",
  which klibs.io was about to put next to badges reading iOS, macOS, Linux and Windows generated from
  the same artifact's own tooling metadata. It did not go stale by accident: everything else moved at
  `2.0.0` and the POM did not, because nothing reads it. The em dashes went with the correction.
- **A credential no longer requires TLS when the host is a loopback address.** A key sent in the clear
  across a network is a key someone else has; a request to `127.0.0.1` never reaches a network. This
  only accepts configurations that were previously rejected, and it is what makes a local Qdrant with
  an API key work without a certificate.
- **Three signatures changed shape, and all three need a recompile rather than a jar swap.**
  `Kdrant(...)` and `KdrantGrpc(...)` gained `decorateTransport`, and `KdrantConfig` gained
  `bearerToken`. Every parameter is optional and every `2.0.0` call site compiles unchanged, but a
  default parameter changes the signature Kotlin emits, so an application compiled against `2.0.0`
  that swaps in the `2.1.0` jar without rebuilding will not find them. This is the case
  [STABILITY.md](STABILITY.md#what-may-still-change-in-a-minor) already describes for data classes,
  now stated for functions and constructors too. `git diff v2.0.0 v2.1.0 -- '*/api/*.api'` shows the
  seven removed lines, and nothing else was removed.
- `KdrantException.Unauthorized` is `open`, so `Forbidden` can extend it. Opening a class removes
  nothing a caller could use.
- `kdrant-transport-rest`'s JVM classes are published as `kdrant-transport-rest-jvm`, the same move
  `kdrant-core` made at `2.0.0`. A Gradle build resolves the variant from the plain coordinate and
  changes nothing; a Maven build naming `kdrant-transport-rest` has to move to the `-jvm` one.

### Fixed

- `ScrollRequest.offset` was documented as the id to start **after**. It is inclusive, which is what
  the paging code has always relied on and what Qdrant returns as `next_page_offset`.
- The count of operations Qdrant serves over HTTP only was eleven in five places and is fourteen.
  It was written into a KDoc once and never counted, and from there it reached the README, this
  changelog, the stability policy and the migration guide. `grep -o 'restOnly("[a-zA-Z]*")' | sort -u |
  wc -l` settles it, and the number now comes from that rather than from memory.
- `STABILITY.md` said `2.0.0` broke nothing but the artifact layout. It broke two things: that, and
  `ScrollRequest`/`SearchRequest` gaining a `shardKey` parameter, which changed their generated `copy`
  and `componentN`. The upgrade section names both.

### Internal

- The release workflow's linked-artifacts step can no longer fail a release. On the `v2.0.0` tag it
  returned 404 for a digest that does have an attestation and took down a job whose jars were already
  published and attested. A metadata step that can undo a successful publish is worth less than the
  metadata, so it is `continue-on-error` with a per-artifact fallback; the run's log still says which
  records were not written.
- The same step's artifact list now names `kdrant-transport-rest-jvm`, and gains `kdrant-otel`,
  `kdrant-migrate-jvm` and `kdrant-koog`, which had been missing since the step shipped.
- CI gained three jobs: the client contract from a `linuxX64` and a `macosArm64` binary, and the
  GraalVM native image. The two native jobs set `KDRANT_QDRANT_REQUIRED`, which turns the contract's
  skip into a failure, because a job that was meant to run it and silently skipped would report green
  for having proven nothing.

## [2.0.0] - 2026-07-31

Tier 5, complete, and the release the transport seam was built for. `kdrant-transport-grpc` is an
opt-in gRPC engine behind the same `QdrantClient`, and `kdrant-core` compiles for the JVM and eight
Kotlin/Native targets. Adding a second engine changed no line of `kdrant-core`.

Two things make this a major. `kdrant-core`'s JVM classes moved to `kdrant-core-jvm`, because the
module is multiplatform now: a Gradle build changes only the version number, a Maven build naming
`kdrant-core` has to move. And `ScrollRequest` and `SearchRequest` gained a `shardKey` parameter, which
changed their generated constructor and `copy`, so code that called `copy()` on either against a `1.x`
jar has to be recompiled. Source stays compatible. The multiplatform migration itself changed no public
API at all. See [STABILITY.md](STABILITY.md#upgrading-from-1-x).

### Added

- **`kdrant-koog`** (M37), a new module: a [Koog](https://github.com/JetBrains/koog) document storage backed by
  Kdrant, so a Koog RAG agent can keep its documents in Qdrant. It implements Koog's search-side storage
  interfaces (`WriteStorage`, `LookupStorage`, `SearchStorage`, `DeletionStorage`) rather than
  `VectorStorageBackend`, which has no search method: Koog's own `EmbeddingStorage` ranks by streaming
  every stored document out of the backend and scoring in memory, and doing that through a vector
  database would mean paying for an index and then pulling the whole collection over the network on
  every query. Here Qdrant runs the search. The module depends only on Koog's stable `rag-base`, not on
  the `rag-vector` beta. Koog's `namespace` becomes a payload field and a filter, so one collection can
  hold several of them.
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
- Formula reranking and MMR (M35), scoped in M16 and not shipped with it. `formula(expression)` rescores the
  candidates a `prefetch` produced with arithmetic over their score and payload: multiply by a
  popularity field, add a bonus for points matching a condition, decay by recency or by distance. The
  `Expression` AST covers Qdrant's full operator set, including the three decay curves and
  `geo_distance`. `mmr(diversity)` reranks a vector query for variety instead of letting ten results
  about the same thing crowd the top.
  Both are validated against Qdrant's published schema by the contract tests, and the bounds Qdrant
  documents (diversity in `0..1`, a positive decay scale, a midpoint in `0..1`) are checked where they
  are written rather than on the round trip.
- Shard-scope snapshots (M36), deferred out of M20 when snapshots first shipped: `createShardSnapshot`,
  `listShardSnapshots`, `deleteShardSnapshot`, `recoverShardSnapshot`, plus streaming
  `downloadShardSnapshot` and `uploadShardSnapshot`. On a sharded collection the existing
  whole-collection snapshot is every shard at once, which on a large collection is the difference
  between a backup that fits in a window and one that does not. Shard ids come from
  `collectionClusterInfo`.
- **`kdrant-transport-grpc`** (M31), the opt-in gRPC engine. `KdrantGrpc(host)` returns the same
  `QdrantClient` the REST factory does, over Qdrant's `Collections`, `Points`, `Snapshots` and `Health`
  services on port **6334**. REST stays the recommended engine; reach for this one when throughput or
  long-lived streaming is the bottleneck, which is the case the README used to concede to the official
  client. Nothing changes for a REST user: the module is separate, and a build that does not ask for it
  resolves no gRPC, no protobuf and no Netty.
  The stubs are generated from Qdrant's own `.proto` files, vendored verbatim at v1.18.2, rather than
  taken from `io.qdrant:client`. grpc-kotlin emits suspend functions and `Flow`s, which is the shape the
  transport seam already has, and generating decides the dependency set instead of inheriting a shaded
  Netty jar that is most of the official client's footprint.
- Both engines are held to one **shared client contract** (M31, `kdrant-testkit`), which runs the same 30
  behavioural tests against a real Qdrant over each protocol. The REST tests that came before it
  asserted HTTP bodies, which a gRPC engine cannot satisfy by construction.
- **`kdrant-core` is a Kotlin Multiplatform library** (M25). It builds for the JVM and for eight
  Kotlin/Native targets: `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `macosX64`,
  `linuxArm64`, `linuxX64` and `mingwX64`. Kotlin/JS is deliberately not among them: there is no JS
  engine, so the target would ship models with nothing to send them over, and its test tooling is the
  only npm dependency graph this repository would have. The models, DSLs, error hierarchy and client
  logic were already free of the JVM, which is what the transport seam was for, so the migration moved
  sources into `commonMain` and changed one declaration. The engines stay JVM-only, because Ktor CIO and
  grpc-java are.
- A `commonTest` suite that runs on every target, covering the places a platform could actually
  differ: the hand-written serializers, the uint64 point id, integer payload values above 2^53, and the
  config's validation.

### Changed

- **`kdrant-core`'s artifact layout changed with the multiplatform move.** The `kdrant-core` coordinate
  now carries Gradle module metadata and the JVM classes live in `kdrant-core-jvm`. A Gradle build
  resolves the right variant from the same coordinate and needs no change; a Maven build names the
  artifact directly and must move to `kdrant-core-jvm`, which the BOM now constrains as well. The
  migration changed no public API: the `*.api` dump is identical either side of it.
- The default dispatcher is platform-dependent, and is the one declaration the migration had to split.
  It stays `Dispatchers.IO` on the JVM. On Kotlin/Native it is `Dispatchers.Default`, because the
  coroutines library still keeps its native IO dispatcher internal. Passing your own dispatcher works
  as before, everywhere.
- Releases are built on macOS. Only a macOS host can compile the Apple targets, so a Linux runner would
  publish a release quietly missing its iOS and macOS klibs.
- `kdrant-core`'s `-javadoc.jar` holds Dokka's HTML output rather than Javadoc HTML: the Dokka Javadoc
  generator refuses a multiplatform project. Maven Central requires the jar to exist rather than to be
  Javadoc, and HTML is what a Kotlin reader wants.
- Fourteen `QdrantTransport` operations have no gRPC equivalent, because the seam was shaped by
  Qdrant's REST API and Qdrant serves these over HTTP only: `telemetry`, `metrics`, `listIssues`,
  `clearIssues`, `recoverSnapshot`, the snapshot and storage-snapshot transfers, and the six
  shard-scope snapshot operations. On the
  gRPC engine each throws an `UnsupportedOperationException` naming the operation and pointing at REST,
  rather than degrading quietly. A snapshot download that returns nothing is a backup that does not
  exist. The REST engine is unchanged.


- Releases publish to Maven Central only. The secondary publication to GitHub Packages is gone: it
  carried the same artifacts to a registry that requires authentication even for public packages, so it
  was a second place to keep in sync and no second way for anyone to depend on Kdrant. Versions up to
  and including `1.2.0` remain on GitHub Packages and are not withdrawn.

### Internal

- Release notes are extracted from this file by the release workflow rather than written by hand. A
  release body composed separately is a second copy of what the changelog owns, and the two eventually
  disagree; derived from here it cannot. The workflow fails the release if the tag has no section.
- Every published jar is recorded as a linked artifact, so the repository's Packages panel names what it
  built and where it went. Metadata, not a distribution channel: nothing is hosted there.
- The set of artifacts the provenance attestation covers is derived from the build instead of listed in
  the workflow. A hardcoded list stops covering a module the day one is added and nothing goes red,
  which is what happened when the gRPC engine arrived.

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

[Unreleased]: https://github.com/NaCode-Studios/Kdrant/compare/v2.2.0...HEAD
[2.2.0]: https://github.com/NaCode-Studios/Kdrant/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/NaCode-Studios/Kdrant/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/NaCode-Studios/Kdrant/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/NaCode-Studios/Kdrant/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/NaCode-Studios/Kdrant/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/NaCode-Studios/Kdrant/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/NaCode-Studios/Kdrant/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NaCode-Studios/Kdrant/releases/tag/v0.1.0
